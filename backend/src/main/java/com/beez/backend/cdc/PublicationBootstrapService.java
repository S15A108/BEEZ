package com.beez.backend.cdc;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit, manually invoked bootstrap of current published baselines.
 *
 * <p>A bootstrap is only a work candidate. The caller must establish a durable
 * live capture boundary before scanning and reconcile/retry incomplete scans.
 * This service neither starts a consumer nor marks any synchronization complete.
 */
@Service
public class PublicationBootstrapService {
    public record Candidate(long projectId, long baselineId, int baselineVersion) {}

    public enum Initialization {
        INITIALIZED, ALREADY_INITIALIZED, NO_PUBLISHED_BASELINE, PROJECT_NOT_FOUND, RECONCILIATION_REQUIRED
    }

    private static final int MAX_PAGE_SIZE = 500;
    private final JdbcClient jdbc;
    private final PublicationProcessingService processing;

    public PublicationBootstrapService(JdbcClient jdbc, PublicationProcessingService processing) {
        this.jdbc = jdbc;
        this.processing = processing;
    }

    /**
     * One keyset page of projects with a current published baseline; no document
     * text or draft rows are returned. Each page is informational, not a
     * global snapshot or proof that bootstrap and live capture have no gap.
     * Archived projects are deliberately not filtered: their policy is not
     * agreed. This internal method is not an authorization-checked endpoint.
     */
    @Transactional(readOnly = true)
    public List<Candidate> candidatesAfter(long afterProjectId, int limit) {
        if (afterProjectId < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Invalid bootstrap page bounds");
        }
        return jdbc.sql("""
                SELECT p.id AS project_id, b.id AS baseline_id, b.version_no
                FROM project p
                JOIN baseline b ON b.id = p.current_baseline_id AND b.project_id = p.id
                WHERE p.id > :after
                ORDER BY p.id
                LIMIT :limit
                """)
                .param("after", afterProjectId).param("limit", limit)
                .query((row, rowNumber) -> new Candidate(row.getLong("project_id"),
                        row.getLong("baseline_id"), row.getInt("version_no")))
                .list();
    }

    /**
     * Re-read and lock the project's current baseline at initialization time.
     * A stale candidate returned by an earlier page is never passed through as
     * the current baseline. Publication paths must also honor the project row
     * lock for this transaction boundary to serialize with publication.
     */
    @Transactional
    public Initialization initializeCurrent(long projectId) {
        if (projectId <= 0) throw new IllegalArgumentException("Invalid project id");
        var projects = jdbc.sql("""
                SELECT current_baseline_id FROM project WHERE id = :project FOR UPDATE
                """).param("project", projectId).query().listOfRows();
        if (projects.isEmpty()) return Initialization.PROJECT_NOT_FOUND;
        Object current = projects.getFirst().get("current_baseline_id");
        if (current == null) return Initialization.NO_PUBLISHED_BASELINE;
        return switch (processing.initialize(projectId, ((Number) current).longValue())) {
            case INITIALIZED -> Initialization.INITIALIZED;
            case ALREADY_INITIALIZED -> Initialization.ALREADY_INITIALIZED;
        };
    }
    /**
     * Preserve the published baseline observed by the scan even when a later
     * publication commits before this project is initialized. The caller must
     * start durable live capture before taking the scan; this method cannot
     * prove capture completeness. Publication and the inbox must use the same
     * project row lock. This is an internal operation, not a public endpoint.
     *
     * Never rewind an existing cursor. If the scanned baseline is absent from
     * an initialized queue, persist a RECONCILE job and block the cursor. An
     * operator must explicitly determine whether recovery is safe.
     */
    @Transactional
    public Initialization initializeScannedCandidate(Candidate candidate) {
        if (candidate == null || candidate.projectId() <= 0 || candidate.baselineId() <= 0
                || candidate.baselineVersion() <= 0) {
            throw new IllegalArgumentException("Invalid bootstrap candidate");
        }
        var projects = jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:project FOR UPDATE")
                .param("project", candidate.projectId()).query().listOfRows();
        if (projects.isEmpty()) return Initialization.PROJECT_NOT_FOUND;
        Object current = projects.getFirst().get("current_baseline_id");
        if (current == null) return Initialization.NO_PUBLISHED_BASELINE;
        long currentId = ((Number) current).longValue();
        boolean valid = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM baseline scanned JOIN baseline current
                  ON current.project_id=scanned.project_id
                  WHERE scanned.id=:scanned AND scanned.project_id=:project
                    AND scanned.version_no=:version AND current.id=:current
                    AND current.version_no>=scanned.version_no)
                """).param("scanned", candidate.baselineId()).param("project", candidate.projectId())
                .param("version", candidate.baselineVersion()).param("current", currentId)
                .query(Boolean.class).single();
        if (!valid) throw new CdcEventException("Scanned baseline is not a published project baseline");

        boolean initialized = jdbc.sql("SELECT EXISTS(SELECT 1 FROM cdc_processing_cursor WHERE project_id=:project)")
                .param("project", candidate.projectId()).query(Boolean.class).single();
        if (initialized) {
            boolean present = jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM cdc_processing_job WHERE project_id=:project
                      AND baseline_id=:baseline AND job_type='PUBLISHED_SYNC')
                    """).param("project", candidate.projectId()).param("baseline", candidate.baselineId())
                    .query(Boolean.class).single();
            if (present) return Initialization.ALREADY_INITIALIZED;
            // A return value alone is not durable: preserve the missed baseline
            // and stop claims under the project lock until recovery is explicit.
            jdbc.sql("""
                    INSERT INTO cdc_processing_job(project_id,baseline_id,baseline_version,state,last_error_code)
                    VALUES(:project,:baseline,:version,'RECONCILE','late_baseline')
                    ON CONFLICT(project_id,baseline_id,job_type) DO NOTHING
                    """).param("project", candidate.projectId()).param("baseline", candidate.baselineId())
                    .param("version", candidate.baselineVersion()).update();
            jdbc.sql("""
                    UPDATE cdc_processing_cursor SET blocked_reason='late_baseline' WHERE project_id=:project
                    """).param("project", candidate.projectId()).update();
            return Initialization.RECONCILIATION_REQUIRED;
        }
        // No event is fabricated for an existing historical baseline. The job
        // is deduplicated with a live event that arrived before this lock.
        jdbc.sql("""
                INSERT INTO cdc_processing_job(project_id,baseline_id,baseline_version)
                VALUES(:project,:baseline,:version)
                ON CONFLICT(project_id,baseline_id,job_type) DO NOTHING
                """).param("project", candidate.projectId()).param("baseline", candidate.baselineId())
                .param("version", candidate.baselineVersion()).update();
        return switch (processing.initialize(candidate.projectId(), currentId)) {
            case INITIALIZED -> Initialization.INITIALIZED;
            case ALREADY_INITIALIZED -> Initialization.ALREADY_INITIALIZED;
        };
    }

}
