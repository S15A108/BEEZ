package com.beez.backend.cdc;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable queue primitives only: no scheduled worker or production analysis is enabled. */
@Service
public class PublicationProcessingService {
    public enum Initialization { INITIALIZED, ALREADY_INITIALIZED }

    /** Result of explicit, internal recovery; no external effect is retried here. */
    public enum RecoveryResult {
        RECOVERED, NOT_INITIALIZED, NOT_BLOCKED, NO_RECOVERY_TARGET,
        LEASE_IN_PROGRESS, MANUAL_REBUILD_REQUIRED
    }

    public record Claim(long jobId, long projectId, long baselineId, int baselineVersion,
                        int attempt, UUID leaseToken) {}

    private final JdbcClient jdbc;

    public PublicationProcessingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Initialize from the current baseline while holding the same project lock as publication. */
    @Transactional
    public Initialization initialize(long projectId, long baselineId) {
        Map<String, Object> project = lockProject(projectId);
        if (existsCursor(projectId)) return Initialization.ALREADY_INITIALIZED;
        if (!isId(project.get("current_baseline_id"), baselineId)) {
            throw new CdcEventException("Bootstrap baseline is no longer current");
        }
        int version = baselineVersion(projectId, baselineId);
        var earliest = jdbc.sql("""
                SELECT min(baseline_version) FROM cdc_processing_job
                  WHERE project_id=:project AND state <> 'SUCCEEDED'
                """).param("project", projectId).query(Integer.class).optional();
        int startVersion = earliest.map(value -> Math.min(version, value)).orElse(version);
        jdbc.sql("""
                INSERT INTO cdc_processing_cursor(project_id,initial_baseline_id,next_version)
                VALUES(:project,:baseline,:version)
                """).param("project", projectId).param("baseline", baselineId).param("version", startVersion).update();
        insertJob(projectId, baselineId, version, null, false);
        return Initialization.INITIALIZED;
    }

    /** Must run in the inbox transaction so a Kafka offset never advances before the job exists. */
    @Transactional
    public boolean enqueue(long projectId, long baselineId, UUID sourceEventId) {
        if (sourceEventId == null) throw new IllegalArgumentException("sourceEventId is required");
        lockProject(projectId);
        boolean valid = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM cdc_publication_inbox
                  WHERE event_id=:event AND project_id=:project AND baseline_id=:baseline)
                """).param("event", sourceEventId).param("project", projectId).param("baseline", baselineId)
                .query(Boolean.class).single();
        if (!valid) throw new CdcEventException("Event is not present in inbox for the project and baseline");
        int version = baselineVersion(projectId, baselineId);
        var existing = jobForBaseline(projectId, baselineId);
        if (existing.isPresent()) {
            // A bootstrap can create the job before the matching live event arrives.
            // Additional event IDs remain in the inbox; never overwrite the first source.
            jdbc.sql("""
                    UPDATE cdc_processing_job SET source_event_id=:event,updated_at=clock_timestamp()
                    WHERE id=:id AND source_event_id IS NULL
                    """).param("event", sourceEventId).param("id", existing.get().get("id")).update();
            return false;
        }
        var cursor = cursor(projectId);
        boolean late = cursor.isPresent() && version < ((Number) cursor.get().get("next_version")).longValue();
        boolean inserted = insertJob(projectId, baselineId, version, sourceEventId, late);
        if (late && inserted) {
            jdbc.sql("UPDATE cdc_processing_cursor SET blocked_reason='late_baseline' WHERE project_id=:project")
                    .param("project", projectId).update();
        }
        return inserted;
    }

    /** Claim only the expected baseline; an absent baseline or job blocks later versions. */
    @Transactional
    public Optional<Claim> claim(long projectId, Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || leaseDuration.compareTo(Duration.ofHours(24)) > 0 || leaseDuration.toMillis() < 1) {
            throw new IllegalArgumentException("Lease must be between 1ms and 24h");
        }
        lockProject(projectId);
        var cursor = cursor(projectId);
        if (cursor.isEmpty()) return Optional.empty();
        if (cursor.get().get("blocked_reason") != null) return Optional.empty();
        var baseline = jdbc.sql("""
                SELECT id,version_no FROM baseline WHERE project_id=:project AND version_no = :version
                """).param("project", projectId).param("version", cursor.get().get("next_version"))
                .query().listOfRows();
        if (baseline.isEmpty()) return Optional.empty();
        long baselineId = ((Number) baseline.getFirst().get("id")).longValue();
        var job = jobForBaseline(projectId, baselineId);
        if (job.isEmpty()) return Optional.empty();
        Map<String, Object> row = job.get();
        long jobId = ((Number) row.get("id")).longValue();
        String state = row.get("state").toString();
        if ("RUNNING".equals(state)) {
            boolean expired = jdbc.sql("SELECT lease_until <= clock_timestamp() FROM cdc_processing_job WHERE id=:id")
                    .param("id", jobId).query(Boolean.class).single();
            if (expired) recordFailure(jobId, ((Number) row.get("attempt_count")).intValue(), "lease_expired");
            return Optional.empty();
        }
        if ("FAILED".equals(state)) return Optional.empty();
        if ("SUCCEEDED".equals(state)) throw new CdcEventException("Cursor is behind a completed job");
        boolean due = jdbc.sql("SELECT next_attempt_at <= clock_timestamp() FROM cdc_processing_job WHERE id=:id")
                .param("id", jobId).query(Boolean.class).single();
        if (!due) return Optional.empty();
        UUID token = UUID.randomUUID();
        var claimed = jdbc.sql("""
                UPDATE cdc_processing_job SET state='RUNNING',attempt_count=attempt_count+1,
                  lease_token=:token,lease_until=clock_timestamp()+(:lease_ms * interval '1 millisecond'),
                  updated_at=clock_timestamp() WHERE id=:id AND state IN ('WAITING','RETRY_WAIT')
                RETURNING attempt_count,baseline_version
                """).param("token", token).param("lease_ms", leaseDuration.toMillis()).param("id", jobId)
                .query().listOfRows();
        if (claimed.size() != 1) throw new CdcEventException("Unable to claim the earliest job");
        Map<String, Object> result = claimed.getFirst();
        return Optional.of(new Claim(jobId, projectId, baselineId,
                ((Number) result.get("baseline_version")).intValue(),
                ((Number) result.get("attempt_count")).intValue(), token));
    }

    /** Call only after an idempotent handler's durable work has completed; no handler is wired yet. */
    @Transactional
    public boolean succeed(Claim claim) {
        lockProject(claim.projectId());
        var cursor = cursor(claim.projectId());
        if (cursor.isEmpty() || cursor.get().get("blocked_reason") != null) return false;
        boolean owned = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM cdc_processing_job WHERE id=:id AND project_id=:project
                  AND baseline_id=:baseline AND baseline_version=:version AND state='RUNNING'
                  AND lease_token=:token AND lease_until > clock_timestamp())
                """).param("id", claim.jobId()).param("project", claim.projectId()).param("baseline", claim.baselineId())
                .param("version", claim.baselineVersion()).param("token", claim.leaseToken())
                .query(Boolean.class).single();
        if (!owned) return false;
        var earliest = jdbc.sql("""
                SELECT id FROM baseline WHERE project_id=:project AND version_no = :version
                """).param("project", claim.projectId()).param("version", cursor.get().get("next_version"))
                .query(Long.class).optional();
        if (earliest.isEmpty() || earliest.get() != claim.baselineId()) {
            jdbc.sql("UPDATE cdc_processing_cursor SET blocked_reason='baseline_reordered' WHERE project_id=:project")
                    .param("project", claim.projectId()).update();
            return false;
        }
        int changed = jdbc.sql("""
                UPDATE cdc_processing_job SET state='SUCCEEDED',lease_token=NULL,lease_until=NULL,
                  updated_at=clock_timestamp() WHERE id=:id AND project_id=:project AND baseline_id=:baseline
                  AND baseline_version=:version
                  AND state='RUNNING' AND lease_token=:token AND lease_until > clock_timestamp()
                """).param("id", claim.jobId()).param("project", claim.projectId())
                .param("baseline", claim.baselineId()).param("version", claim.baselineVersion())
                .param("token", claim.leaseToken()).update();
        if (changed == 0) return false;
        int advanced = jdbc.sql("""
                UPDATE cdc_processing_cursor SET next_version = CAST(:version AS bigint) + 1
                WHERE project_id=:project AND next_version <= :version
                """).param("version", claim.baselineVersion()).param("project", claim.projectId()).update();
        if (advanced != 1) throw new CdcEventException("Unable to advance processing cursor");
        return true;
    }

    /** Error codes are bounded identifiers, never model output or document content. */
    @Transactional
    public boolean fail(Claim claim, String errorCode) {
        if (errorCode == null || !errorCode.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("Invalid error code");
        }
        lockProject(claim.projectId());
        var rows = jdbc.sql("""
                SELECT attempt_count FROM cdc_processing_job WHERE id=:id AND project_id=:project
                  AND baseline_id=:baseline AND state='RUNNING' AND lease_token=:token
                  AND lease_until > clock_timestamp()
                """).param("id", claim.jobId()).param("project", claim.projectId())
                .param("baseline", claim.baselineId()).param("token", claim.leaseToken()).query().listOfRows();
        if (rows.isEmpty()) return false;
        recordFailure(claim.jobId(), ((Number) rows.getFirst().get("attempt_count")).intValue(), errorCode);
        return true;
    }

    /**
     * Rewind only when no affected job has ever run. A running or expired
     * lease can still have an external side effect, and even failed attempts
     * may have partially succeeded outside this database. Such cases require
     * a coordinated manual rebuild and must never be reset by this method.
     *
     * This internal primitive does not authenticate an operator, inspect the
     * actual downstream service, or establish capture completeness. It must
     * not be exposed without an authorization and reconciliation contract.
     */
    @Transactional
    public RecoveryResult recoverUnattemptedReordering(long projectId) {
        lockProject(projectId);
        var current = cursor(projectId);
        if (current.isEmpty()) return RecoveryResult.NOT_INITIALIZED;
        if (current.get().get("blocked_reason") == null) return RecoveryResult.NOT_BLOCKED;

        boolean activeLease = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM cdc_processing_job
                  WHERE project_id=:project AND state='RUNNING' AND lease_until > clock_timestamp())
                """).param("project", projectId).query(Boolean.class).single();
        if (activeLease) return RecoveryResult.LEASE_IN_PROGRESS;

        // Expiry is not proof that the previous worker stopped or produced no side effects.
        boolean expiredLease = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM cdc_processing_job
                  WHERE project_id=:project AND state='RUNNING')
                """).param("project", projectId).query(Boolean.class).single();
        if (expiredLease) return RecoveryResult.MANUAL_REBUILD_REQUIRED;

        var reconcile = jdbc.sql("""
                SELECT min(baseline_version) FROM cdc_processing_job
                  WHERE project_id=:project AND state='RECONCILE'
                """).param("project", projectId).query(Integer.class).optional();
        long cursorVersion = ((Number) current.get().get("next_version")).longValue();
        long targetVersion = reconcile.map(version -> Math.min((long) version, cursorVersion))
                .orElse(cursorVersion);
        boolean targetExists = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM cdc_processing_job
                  WHERE project_id=:project AND baseline_version=:version
                    AND state IN ('WAITING','RECONCILE'))
                """).param("project", projectId).param("version", targetVersion)
                .query(Boolean.class).single();
        if (!targetExists) return RecoveryResult.NO_RECOVERY_TARGET;

        boolean attempted = jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM cdc_processing_job
                  WHERE project_id=:project AND baseline_version>=:version
                    AND (state='SUCCEEDED' OR attempt_count>0 OR retry_cycle>0))
                """).param("project", projectId).param("version", targetVersion)
                .query(Boolean.class).single();
        if (attempted) return RecoveryResult.MANUAL_REBUILD_REQUIRED;

        jdbc.sql("""
                UPDATE cdc_processing_job SET state='WAITING',updated_at=clock_timestamp()
                WHERE project_id=:project AND state='RECONCILE'
                """).param("project", projectId).update();
        int changed = jdbc.sql("""
                UPDATE cdc_processing_cursor SET next_version=:version,blocked_reason=NULL
                WHERE project_id=:project AND blocked_reason IS NOT NULL
                """).param("project", projectId).param("version", targetVersion).update();
        if (changed != 1) throw new CdcEventException("Unable to recover processing cursor");
        return RecoveryResult.RECOVERED;
    }

    /** Explicitly retry a failed job without claiming downstream work succeeded. */
    @Transactional
    public boolean retryFailed(long projectId, long jobId) {
        lockProject(projectId);
        return jdbc.sql("""
                UPDATE cdc_processing_job SET state='WAITING',attempt_count=0,retry_cycle=retry_cycle+1,
                  next_attempt_at=clock_timestamp(),updated_at=clock_timestamp()
                WHERE id=:id AND project_id=:project AND state='FAILED'
                """).param("id", jobId).param("project", projectId).update() == 1;
    }

    private void recordFailure(long jobId, int attempt, String code) {
        boolean exhausted = attempt >= 4;
        long delay = switch (attempt) { case 1 -> 1; case 2 -> 5; case 3 -> 30; default -> 0; };
        jdbc.sql("""
                UPDATE cdc_processing_job SET state=:state,last_error_code=:code,
                  next_attempt_at=clock_timestamp()+(:delay * interval '1 second'),
                  lease_token=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE id=:id
                """).param("state", exhausted ? "FAILED" : "RETRY_WAIT").param("code", code)
                .param("delay", delay).param("id", jobId).update();
    }

    private Map<String, Object> lockProject(long projectId) {
        var rows = jdbc.sql("SELECT id,current_baseline_id FROM project WHERE id=:id FOR UPDATE")
                .param("id", projectId).query().listOfRows();
        if (rows.isEmpty()) throw new CdcEventException("Project not found");
        return rows.getFirst();
    }

    private int baselineVersion(long projectId, long baselineId) {
        var rows = jdbc.sql("SELECT version_no FROM baseline WHERE id=:baseline AND project_id=:project")
                .param("baseline", baselineId).param("project", projectId).query(Integer.class).list();
        if (rows.isEmpty()) throw new CdcEventException("Baseline not found for project");
        return rows.getFirst();
    }

    private Optional<Map<String, Object>> cursor(long projectId) {
        return jdbc.sql("SELECT * FROM cdc_processing_cursor WHERE project_id=:project")
                .param("project", projectId).query().listOfRows().stream().findFirst();
    }

    private boolean existsCursor(long projectId) { return cursor(projectId).isPresent(); }

    private Optional<Map<String, Object>> jobForBaseline(long projectId, long baselineId) {
        return jdbc.sql("SELECT * FROM cdc_processing_job WHERE project_id=:project AND baseline_id=:baseline AND job_type='PUBLISHED_SYNC'")
                .param("project", projectId).param("baseline", baselineId).query().listOfRows().stream().findFirst();
    }

    private boolean insertJob(long projectId, long baselineId, int version, UUID eventId, boolean late) {
        return jdbc.sql("""
                INSERT INTO cdc_processing_job(project_id,baseline_id,baseline_version,source_event_id,state,last_error_code)
                VALUES(:project,:baseline,:version,:event,:state,:error) ON CONFLICT(project_id,baseline_id,job_type) DO NOTHING
                """).param("project", projectId).param("baseline", baselineId).param("version", version)
                .param("event", eventId).param("state", late ? "RECONCILE" : "WAITING")
                .param("error", late ? "late_baseline" : null).update() == 1;
    }

    private static boolean isId(Object value, long expected) {
        return value instanceof Number number && number.longValue() == expected;
    }
}
