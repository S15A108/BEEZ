package com.beez.backend.impactanalysis.intake;

import com.beez.backend.cdc.recovery.*;
import com.beez.backend.changeproposal.port.PostPublishAnalysisIntake;
import com.beez.backend.cdc.retry.ProcessingRetryPolicy;
import static com.beez.backend.designitem.support.DesignJson.canonical;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import com.beez.backend.usage.repository.LlmUsageRecorder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

/** Durable data operations only. #227 owns execution and recurring recovery sweeps. */
@Repository
public class PublishedAnalysisRepository implements IntakeRecoveryScanPort, PostPublishAnalysisIntake {
    private final JdbcClient jdbc;
    private final LlmUsageRecorder usageRecorder;
    public PublishedAnalysisRepository(JdbcClient jdbc, LlmUsageRecorder usageRecorder) {
        this.jdbc = jdbc;
        this.usageRecorder = usageRecorder;
    }

    @Override @Transactional(propagation = Propagation.MANDATORY)
    public void accept(long project, long baseline, Long proposal) {
        jdbc.sql("""
                INSERT INTO published_change_analysis(project_id,published_baseline_id,change_proposal_id,status,finished_at)
                VALUES(:project,:baseline,:proposal,:status,CASE WHEN :proposal IS NULL THEN clock_timestamp() END)
                ON CONFLICT(project_id,published_baseline_id) DO NOTHING
                """).param("project", project).param("baseline", baseline).param("proposal", proposal, java.sql.Types.BIGINT)
                .param("status", proposal == null ? "NOT_EVALUATED" : "PENDING").update();
    }

    @Transactional(readOnly = true)
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
                ((status='PENDING' AND ((next_attempt_at IS NULL AND requested_at<=:threshold)
                   OR (next_attempt_at IS NOT NULL AND next_attempt_at<=:now)))
                  OR (status='RUNNING' AND lease_until<=:now)) ORDER BY id LIMIT :limit
                """).param("cursor", cursor == null ? 0L : parse(cursor)).param("threshold", Timestamp.from(observedAt.minusSeconds(30)))
                .param("now", Timestamp.from(observedAt)).param("limit", limit + 1).query().listOfRows();
        var selected = rows.subList(0, Math.min(limit, rows.size()));
        var candidates = selected.stream().map(row -> new IntakeRecoveryCandidate(row.get("id").toString(),
                "PENDING".equals(row.get("status")) ? IntakeRecoveryCandidate.Kind.PENDING : IntakeRecoveryCandidate.Kind.EXPIRED_RUNNING)).toList();
        return new Page(candidates, rows.size() > limit ? selected.getLast().get("id").toString() : null);
    }

    public record Claim(long id, int attempt, Instant leaseUntil) {}
    public record Publication(long projectId, long baselineId, long proposalId, long publishedBy) {}

    @Transactional(readOnly = true)
    public Publication publication(long id) {
        return jdbc.sql("""
                SELECT a.project_id,a.published_baseline_id,a.change_proposal_id,b.published_by
                  FROM published_change_analysis a JOIN baseline b
                    ON b.id=a.published_baseline_id AND b.project_id=a.project_id
                 WHERE a.id=:id AND a.change_proposal_id IS NOT NULL
                   AND b.change_proposal_id=a.change_proposal_id
                """).param("id", id).query((rs, row) -> new Publication(rs.getLong(1),rs.getLong(2),
                        rs.getLong(3),rs.getLong(4))).optional()
                .orElseThrow(() -> new IllegalStateException("Invalid publication intake"));
    }

    @Transactional
    public Optional<Claim> claimNow(long id, long leaseSeconds) {
        var project = jdbc.sql("SELECT project_id FROM published_change_analysis WHERE id=:id")
                .param("id", id).query(Long.class).optional();
        if (project.isEmpty()) return Optional.empty();
        jdbc.sql("SELECT id FROM project WHERE id=:project FOR UPDATE").param("project", project.get()).query(Long.class).single();
        return claim(id, databaseTime(), leaseSeconds);
    }

    public Instant databaseTime() {
        return jdbc.sql("SELECT clock_timestamp()").query((rs, row) -> rs.getTimestamp(1).toInstant()).single();
    }

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
                  AND NOT EXISTS (SELECT 1 FROM baseline current
                    CROSS JOIN LATERAL generate_series(1,current.version_no-1) required(version_no)
                    WHERE current.id=a.published_baseline_id AND current.project_id=a.project_id
                      AND NOT EXISTS (SELECT 1 FROM baseline b WHERE b.project_id=a.project_id AND b.version_no=required.version_no))
                  AND NOT EXISTS (SELECT 1 FROM baseline eb
                    JOIN baseline current ON current.id=a.published_baseline_id AND current.project_id=a.project_id
                    LEFT JOIN published_change_analysis earlier ON earlier.published_baseline_id=eb.id AND earlier.project_id=eb.project_id
                    WHERE eb.project_id=a.project_id AND eb.version_no<current.version_no
                      AND (earlier.id IS NULL OR NOT COALESCE(earlier.status='COMPLETED' OR
                        (earlier.status='NOT_EVALUATED' AND
                          ((eb.version_no=1 AND earlier.change_proposal_id IS NULL) OR earlier.error_code='published_before_intake')), false)))
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
                  AND lease_until>clock_timestamp()
                """).param("id", claim.id()).param("attempt", claim.attempt()).param("lease", Timestamp.from(claim.leaseUntil()))
                .param("now", Timestamp.from(now)).param("status", status).param("code", code, java.sql.Types.VARCHAR)
                .param("next", next == null ? null : next.atOffset(java.time.ZoneOffset.UTC), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update() == 1;
    }

    /** Must run in the same transaction as the project lock and final freshness check. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordResult(Claim claim, String evaluation, String code, String fingerprint,
                                Object explicit, Object ai, Object generation, Object usage, String usageStatus) {
        if (!Set.of("COMPLETED", "INSUFFICIENT_EVIDENCE", "NOT_EVALUATED").contains(evaluation))
            throw new IllegalArgumentException("Invalid evaluation status");
        String lifecycle = "NOT_EVALUATED".equals(evaluation) ? "NOT_EVALUATED" : "COMPLETED";
        int updated = jdbc.sql("""
                UPDATE published_change_analysis SET status=:lifecycle,evaluation_status=:evaluation,error_code=:code,
                  input_fingerprint=:fingerprint,explicit_result=cast(:explicit AS jsonb),ai_result=cast(:ai AS jsonb),
                  generation=cast(:generation AS jsonb),usage=cast(:usage AS jsonb),usage_status=:usageStatus,
                  finished_at=clock_timestamp(),lease_until=NULL,next_attempt_at=NULL
                 WHERE id=:id AND status='RUNNING' AND attempt=:attempt AND lease_until=:lease AND lease_until>clock_timestamp()
                """).param("id", claim.id()).param("attempt", claim.attempt()).param("lease", Timestamp.from(claim.leaseUntil()))
                .param("lifecycle", lifecycle).param("evaluation", evaluation).param("code", code, java.sql.Types.VARCHAR)
                .param("fingerprint", fingerprint).param("explicit", canonical(explicit)).param("ai", canonical(ai))
                .param("generation", canonical(generation)).param("usage", canonical(usage)).param("usageStatus", usageStatus).update();
        if (updated == 1) insertAttempt(claim, evaluation, code, usage, usageStatus);
        return updated == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordFailure(Claim claim, String code, boolean retryable, Object usage, String usageStatus) {
        Instant now = databaseTime();
        if (!fail(claim, now, code, retryable)) return false;
        insertAttempt(claim, "FAILED", code, usage, usageStatus);
        return true;
    }

    /**
     * 모델을 부른 사용량을 공통 사용량 표(llm_usage)에 남긴다 — 비용 조회(MON-A02)는 그 표만 읽는다 (-169).
     * 시도 기록과 따로 부른다: 결과를 쓰지 못한 경우(리스를 잃음·권한 재확인 실패·저장 실패)나 시도 기록에 넣기에 너무 큰 사용량도
     * 실제 호출 비용이므로 남긴다. 같은 호출은 request_key 로 한 번만. 항목에 model 이 없으면 응답의 model, 그것도 없으면 "unknown"
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUsage(Claim claim, Object usage, String model) {
        var project = jdbc.sql("SELECT project_id FROM published_change_analysis WHERE id=:id").param("id", claim.id())
                .query(Long.class).optional();
        if (project.isEmpty()) return;
        String fallback = model == null || model.isBlank() ? "unknown" : model;
        usageRecorder.record(new LlmUsageRecorder.Owner(project.get(), "CHANGE_IMPACT", null, null, fallback,
                "published analysis " + claim.id() + " attempt " + claim.attempt()), usage, 0);
    }

    private void insertAttempt(Claim claim, String outcome, String code, Object usage, String usageStatus) {
        jdbc.sql("""
                INSERT INTO published_analysis_attempt(intake_id,attempt,outcome,error_code,usage,usage_status)
                VALUES(:id,:attempt,:outcome,:code,cast(:usage AS jsonb),:usageStatus)
                """).param("id", claim.id()).param("attempt", claim.attempt()).param("outcome", outcome)
                .param("code", code, java.sql.Types.VARCHAR).param("usage", canonical(usage)).param("usageStatus", usageStatus).update();
    }

    private static long parse(String value) {
        try { long id = Long.parseLong(value); if (id > 0) return id; }
        catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Invalid intake reference");
    }
}
