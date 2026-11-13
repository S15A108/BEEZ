package com.beez.backend.cdc.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.changeproposal.repository.ProposalRepository;
import com.beez.backend.usage.repository.LlmUsageRecorder;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Optional live-PostgreSQL SQL test; uses only temporary tables in its own connection. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "BEEZ_CDC_TEST_DATABASE_URL", matches = "jdbc:postgresql:.*")
class PublishedAnalysisPostgresIntegrationTest {
    @Test
    void aggregateAndWorkerUseTheSamePredecessorAndEvaluationSemantics() {
        var source = new SingleConnectionDataSource(System.getenv("BEEZ_CDC_TEST_DATABASE_URL"), "beez_test", "", true);
        try {
            var sql = new JdbcTemplate(source);
            sql.execute("CREATE TEMP TABLE project(id bigint PRIMARY KEY)");
            sql.execute("CREATE TEMP TABLE baseline(id bigint PRIMARY KEY, project_id bigint NOT NULL, version_no integer NOT NULL)");
            sql.execute("""
                    CREATE TEMP TABLE published_change_analysis(
                      id bigint PRIMARY KEY, project_id bigint NOT NULL, published_baseline_id bigint NOT NULL,
                      change_proposal_id bigint, status text NOT NULL, evaluation_status text, error_code text,
                      attempt integer NOT NULL DEFAULT 0, requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
                      started_at timestamptz, finished_at timestamptz, lease_until timestamptz, next_attempt_at timestamptz)
                    """);
            sql.execute("CREATE TEMP TABLE published_analysis_attempt(intake_id bigint, attempt integer, outcome text)");
            sql.update("INSERT INTO project VALUES (1),(2),(3)");
            sql.update("INSERT INTO baseline VALUES (101,1,1),(102,1,2),(103,1,3),"
                    + "(201,2,1),(203,2,3),(301,3,1),(302,3,2),(303,3,3)");
            sql.update("""
                    INSERT INTO published_change_analysis(id,project_id,published_baseline_id,change_proposal_id,status,error_code,attempt,requested_at)
                    VALUES (1001,1,101,NULL,'NOT_EVALUATED',NULL,0,clock_timestamp()),
                           (1002,1,102,90,'FAILED','analysis_interrupted',2,clock_timestamp()),
                           (1003,1,103,91,'PENDING',NULL,0,clock_timestamp()-interval '3 minutes'),
                           (2001,2,201,NULL,'NOT_EVALUATED',NULL,0,clock_timestamp()),
                           (2003,2,203,92,'PENDING',NULL,0,clock_timestamp()),
                           (3001,3,301,NULL,'NOT_EVALUATED',NULL,0,clock_timestamp()),
                           (3002,3,302,93,'NOT_EVALUATED','published_before_intake',0,clock_timestamp()),
                           (3003,3,303,94,'PENDING',NULL,0,clock_timestamp())
                    """);
            sql.update("INSERT INTO published_analysis_attempt VALUES (1002,1,'FAILED'),(1002,2,'INSUFFICIENT_EVIDENCE')");
            // A stale evaluation attached to an in-flight row must not count as completed.
            sql.update("UPDATE published_change_analysis SET evaluation_status='INSUFFICIENT_EVIDENCE' WHERE id=1003");

            var client = JdbcClient.create(source);
            var reader = new PublishedAnalysisMetricsReader(client);
            var repository = new PublishedAnalysisRepository(client, mock(LlmUsageRecorder.class));
            var proposalRepository = new ProposalRepository(client);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            assertThat(proposalRepository.publishedAnalysis(1, 90, 102)).hasValueSatisfying(row ->
                    assertThat(row).containsEntry("status", "FAILED").containsEntry("attempt", 2));
            assertThat(proposalRepository.publishedAnalysis(2, 90, 102)).isEmpty();
            assertThat(proposalRepository.publishedAnalysis(1, 91, 102)).isEmpty();
            var snapshot = reader.read();
            assertThat(snapshot.intakes().get("PENDING")).isEqualTo(3);
            assertThat(snapshot.intakes().get("FAILED")).isEqualTo(1);
            assertThat(snapshot.notEvaluatedExpected()).isEqualTo(4);
            assertThat(snapshot.notEvaluatedRequiresReview()).isZero();
            // 끝난(FAILED) 앞 버전은 막지 않는다. 버전 2가 빠진 프로젝트 2만 막힌다 (S15P31A108-408)
            assertThat(snapshot.blockedProjects()).isEqualTo(1);
            assertThat(snapshot.oldestPendingSeconds()).isGreaterThanOrEqualTo(180);
            assertThat(snapshot.startedRetries()).isEqualTo(1);
            assertThat(snapshot.recordedRetryAttempts()).isEqualTo(1);
            assertThat(snapshot.attempts().get("FAILED")).isEqualTo(1);
            assertThat(snapshot.evaluations().get("INSUFFICIENT_EVIDENCE")).isZero();

            assertThat(claimed(tx, repository, 1003)).isTrue();
            assertThat(claimed(tx, repository, 2003)).isFalse();
            assertThat(claimed(tx, repository, 3003)).isTrue();

            // 아직 처리 중인 앞 버전은 기다린다
            sql.update("UPDATE published_change_analysis SET status='PENDING',attempt=0,lease_until=NULL WHERE id=1003");
            sql.update("UPDATE published_change_analysis SET status='RUNNING',lease_until=clock_timestamp()+interval '1 minute' WHERE id=1002");
            assertThat(reader.read().blockedProjects()).isEqualTo(2);
            assertThat(claimed(tx, repository, 1003)).isFalse();

            sql.update("UPDATE published_change_analysis SET status='COMPLETED',evaluation_status='INSUFFICIENT_EVIDENCE',lease_until=NULL WHERE id=1002");
            assertThat(reader.read().blockedProjects()).isEqualTo(1);
            assertThat(claimed(tx, repository, 1003)).isTrue();
            assertThat(reader.read().evaluations().get("INSUFFICIENT_EVIDENCE")).isEqualTo(1);

            // 사유 없는 미평가도 막지 않지만 확인 필요 지표에는 남는다
            sql.update("UPDATE published_change_analysis SET status='NOT_EVALUATED',evaluation_status='NOT_EVALUATED',error_code=NULL WHERE id=1002");
            sql.update("UPDATE published_change_analysis SET status='PENDING',lease_until=NULL WHERE id=1003");
            assertThat(reader.read().blockedProjects()).isEqualTo(1);
            assertThat(reader.read().notEvaluatedRequiresReview()).isEqualTo(1);
            assertThat(claimed(tx, repository, 1003)).isTrue();
            sql.update("UPDATE published_change_analysis SET status='COMPLETED',lease_until=NULL WHERE status IN ('PENDING','RUNNING')");
            assertThat(reader.read().oldestPendingSeconds()).isZero();
            assertThat(reader.read().blockedProjects()).isZero();
            sql.update("UPDATE published_change_analysis SET evaluation_status='NOT_EVALUATED' WHERE id=2003");
            assertThat(reader.read().evaluations().get("UNKNOWN")).isGreaterThanOrEqualTo(1);
        } finally {
            source.destroy();
        }
    }

    private static boolean claimed(TransactionTemplate tx, PublishedAnalysisRepository repository, long id) {
        var result = tx.execute(s -> repository.claim(id, Instant.now(), 60));
        return result != null && result.isPresent();
    }
}
