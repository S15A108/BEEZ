package com.beez.backend.cdc.observability;

import com.beez.backend.impactanalysis.intake.PublishedAnalysisOrderingSql;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Never returns project identifiers, source content, error messages, or dynamic labels. */
@Repository
public class PublishedAnalysisMetricsReader {
    private final JdbcClient jdbc;

    public PublishedAnalysisMetricsReader(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 5)
    public PublishedAnalysisMetricsSnapshot read() {
        var intake = jdbc.sql("""
                SELECT count(*) FILTER (WHERE a.status='PENDING') AS pending,
                       count(*) FILTER (WHERE a.status='RUNNING') AS running,
                       count(*) FILTER (WHERE a.status='COMPLETED') AS completed,
                       count(*) FILTER (WHERE a.status='FAILED') AS failed,
                       count(*) FILTER (WHERE a.status='NOT_EVALUATED') AS not_evaluated,
                       count(*) FILTER (WHERE a.status='NOT_EVALUATED' AND
                         COALESCE((b.version_no=1 AND a.change_proposal_id IS NULL)
                           OR a.error_code='published_before_intake', false)) AS not_evaluated_expected,
                       count(*) FILTER (WHERE a.status='NOT_EVALUATED' AND NOT
                         COALESCE((b.version_no=1 AND a.change_proposal_id IS NULL)
                           OR a.error_code='published_before_intake', false)) AS not_evaluated_review,
                       count(*) FILTER (WHERE a.status='COMPLETED' AND a.evaluation_status='COMPLETED') AS evaluation_completed,
                       count(*) FILTER (WHERE a.status='COMPLETED' AND a.evaluation_status='INSUFFICIENT_EVIDENCE') AS insufficient_evidence,
                       count(*) FILTER (WHERE a.status='COMPLETED' AND (a.evaluation_status IS NULL
                         OR a.evaluation_status NOT IN ('COMPLETED','INSUFFICIENT_EVIDENCE'))) AS evaluation_unknown,
                       count(*) FILTER (WHERE a.status='PENDING' AND a.next_attempt_at IS NOT NULL) AS pending_retries,
                       count(*) FILTER (WHERE a.status='RUNNING' AND a.lease_until <= statement_timestamp()) AS expired_leases,
                       coalesce(sum(greatest(a.attempt-1,0)),0) AS started_retries,
                       COALESCE(GREATEST(0, EXTRACT(EPOCH FROM (statement_timestamp()
                           - min(a.requested_at) FILTER (WHERE a.status='PENDING')))), 0) AS oldest_pending_seconds
                  FROM published_change_analysis a JOIN baseline b
                    ON b.id=a.published_baseline_id AND b.project_id=a.project_id
                """).query().singleRow();
        var attempts = jdbc.sql("""
                SELECT count(*) FILTER (WHERE outcome='COMPLETED') AS completed,
                       count(*) FILTER (WHERE outcome='INSUFFICIENT_EVIDENCE') AS insufficient_evidence,
                       count(*) FILTER (WHERE outcome='NOT_EVALUATED') AS not_evaluated,
                       count(*) FILTER (WHERE outcome='FAILED') AS failed,
                       count(*) FILTER (WHERE attempt > 1) AS retries
                  FROM published_analysis_attempt
                """).query().singleRow();
        long blocked = jdbc.sql("""
                SELECT count(DISTINCT a.project_id) FROM published_change_analysis a
                 WHERE a.status='PENDING' AND NOT (%s)
                """.formatted(PublishedAnalysisOrderingSql.PREDECESSORS_READY)).query(Long.class).single();
        return new PublishedAnalysisMetricsSnapshot(
                Map.of("PENDING", number(intake, "pending"), "RUNNING", number(intake, "running"),
                        "COMPLETED", number(intake, "completed"), "FAILED", number(intake, "failed"),
                        "NOT_EVALUATED", number(intake, "not_evaluated")),
                Map.of("COMPLETED", number(intake, "evaluation_completed"),
                        "INSUFFICIENT_EVIDENCE", number(intake, "insufficient_evidence"),
                        "UNKNOWN", number(intake, "evaluation_unknown")),
                Map.of("COMPLETED", number(attempts, "completed"),
                        "INSUFFICIENT_EVIDENCE", number(attempts, "insufficient_evidence"),
                        "NOT_EVALUATED", number(attempts, "not_evaluated"), "FAILED", number(attempts, "failed")),
                number(intake, "not_evaluated_expected"), number(intake, "not_evaluated_review"), blocked,
                number(intake, "pending_retries"), number(intake, "expired_leases"), number(intake, "started_retries"), number(attempts, "retries"),
                number(intake, "oldest_pending_seconds"));
    }

    private static double number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).doubleValue();
    }
}
