package com.beez.backend.impactanalysis.intake;

import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import com.beez.backend.cdc.recovery.*;
import com.beez.backend.cdc.retry.ProcessingRetryPolicy;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Durable data operations only. #227 owns execution and recurring recovery sweeps. */
@Repository
public class PublishedAnalysisRepository implements AnalysisIntakeCheckPort, IntakeRecoveryScanPort {
    private final JdbcClient jdbc;
    public PublishedAnalysisRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(long project, long baseline, Long proposal) {
        jdbc.sql("""
                INSERT INTO published_change_analysis(project_id,published_baseline_id,change_proposal_id,status,finished_at)
                VALUES(:project,:baseline,:proposal,:status,CASE WHEN :proposal IS NULL THEN clock_timestamp() END)
                ON CONFLICT(project_id,published_baseline_id) DO NOTHING
                """).param("project", project).param("baseline", baseline).param("proposal", proposal, java.sql.Types.BIGINT)
                .param("status", proposal == null ? "NOT_EVALUATED" : "PENDING").update();
    }

    @Override @Transactional(readOnly = true)
    public void ensureSafeToAcknowledge(String reference) {
        // Discovery is durable even when delivery is duplicated; no in-memory claim or execution here.
        if (jdbc.sql("SELECT id FROM published_change_analysis WHERE id=:id").param("id", parse(reference))
                .query(Long.class).optional().isEmpty()) throw new IllegalStateException("Analysis intake missing");
    }

    @Override @Transactional(readOnly = true)
    public Page findCandidates(Instant observedAt, int limit, String cursor) {
        Objects.requireNonNull(observedAt);
        if (limit < 1 || limit > IntakeRecoverySweep.MAX_PAGE_SIZE) throw new IllegalArgumentException("Invalid limit");
        var rows = jdbc.sql("""
                SELECT id,status FROM published_change_analysis WHERE id>:cursor AND
                ((status='PENDING' AND requested_at<=:threshold AND (next_attempt_at IS NULL OR next_attempt_at<=:now))
                  OR (status='RUNNING' AND lease_until<=:now)) ORDER BY id LIMIT :limit
                """).param("cursor", cursor == null ? 0L : parse(cursor)).param("threshold", Timestamp.from(observedAt.minusSeconds(30)))
                .param("now", Timestamp.from(observedAt)).param("limit", limit + 1).query().listOfRows();
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        var candidates = selected.stream().map(row -> new IntakeRecoveryCandidate(row.get("id").toString(),
                "PENDING".equals(row.get("status")) ? IntakeRecoveryCandidate.Kind.PENDING : IntakeRecoveryCandidate.Kind.EXPIRED_RUNNING)).toList();
        return new Page(candidates, rows.size() > limit ? selected.getLast().get("id").toString() : null);
    }

    public record Claim(long id, int attempt, Instant leaseUntil) {}

    @Transactional
    public Optional<Claim> claim(long id, Instant now, long leaseSeconds) {
        if (leaseSeconds < 1) throw new IllegalArgumentException("Invalid lease");
        // Serialize claims with publication and other claims in this project, before rechecking order.
        var project = jdbc.sql("SELECT project_id FROM published_change_analysis WHERE id=:id")
                .param("id", id).query(Long.class).optional();
        if (project.isEmpty()) return Optional.empty();
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE").param("project", project.get()).query(Long.class).single();
        // An expired final attempt must block later baselines, rather than becoming unclaimable RUNNING forever.
        jdbc.sql("""
                UPDATE published_change_analysis SET status='FAILED',error_code='analysis_interrupted',finished_at=:now
                 WHERE id=:id AND status='RUNNING' AND attempt=4 AND lease_until<=:now
                """).param("id", id).param("now", Timestamp.from(now)).update();
        return jdbc.sql("""
                UPDATE published_change_analysis a SET status='RUNNING',attempt=attempt+1,started_at=:now,
                  lease_until=:lease,next_attempt_at=NULL,error_code=NULL,finished_at=NULL
                WHERE a.id=:id AND a.attempt<4
                  AND ((a.status='PENDING' AND (a.next_attempt_at IS NULL OR a.next_attempt_at<=:now))
                    OR (a.status='RUNNING' AND a.lease_until<=:now))
                  AND NOT EXISTS (SELECT 1 FROM published_change_analysis earlier
                    JOIN baseline eb ON eb.id=earlier.published_baseline_id
                    JOIN baseline current ON current.id=a.published_baseline_id
                    WHERE earlier.project_id=a.project_id AND eb.version_no<current.version_no
                      AND earlier.status IN ('PENDING','RUNNING','FAILED'))
                RETURNING id,attempt,lease_until
                """).param("id", id).param("now", Timestamp.from(now)).param("lease", Timestamp.from(now.plusSeconds(leaseSeconds)))
                .query((rs, n) -> new Claim(rs.getLong("id"), rs.getInt("attempt"), rs.getTimestamp("lease_until").toInstant())).optional();
    }

    @Transactional
    public boolean complete(Claim claim, Instant now) { return finish(claim, now, "COMPLETED", null, null); }

    @Transactional
    public boolean fail(Claim claim, Instant now, String code, boolean retryable) {
        if (code == null || !code.matches("[a-z][a-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid error code");
        var decision = ProcessingRetryPolicy.afterFailure(claim.attempt(), now);
        Instant next = retryable && decision instanceof ProcessingRetryPolicy.Retry retry ? retry.notBefore() : null;
        return finish(claim, now, next == null ? "FAILED" : "PENDING", code, next);
    }

    private boolean finish(Claim claim, Instant now, String status, String code, Instant next) {
        return jdbc.sql("""
                UPDATE published_change_analysis SET status=:status,error_code=:code,next_attempt_at=:next,
                  finished_at=CASE WHEN :status='PENDING' THEN NULL ELSE cast(:now AS timestamptz) END,lease_until=NULL
                WHERE id=:id AND status='RUNNING' AND attempt=:attempt AND lease_until=:lease AND lease_until>:now
                """).param("id", claim.id()).param("attempt", claim.attempt()).param("lease", Timestamp.from(claim.leaseUntil()))
                .param("now", Timestamp.from(now)).param("status", status).param("code", code, java.sql.Types.VARCHAR)
                .param("next", next == null ? null : next.atOffset(java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update() == 1;
    }

    private static long parse(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid intake reference");
    }
}
