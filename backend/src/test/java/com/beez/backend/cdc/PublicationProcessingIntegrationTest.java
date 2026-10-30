package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.cdc.PublicationProcessingService.Claim;
import com.beez.backend.cdc.PublicationProcessingService.Initialization;
import com.beez.backend.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicationProcessingIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationProcessingService processing;

    private record Scenario(long projectId, long first, long second, long third) {}

    private Scenario scenario() {
        var project = fixtures().projectWithLeader();
        long first = baseline(project.projectId(), project.leaderAccountId(), 1);
        long second = baseline(project.projectId(), project.leaderAccountId(), 2);
        long third = baseline(project.projectId(), project.leaderAccountId(), 3);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", first, project.projectId());
        return new Scenario(project.projectId(), first, second, third);
    }

    private long baseline(long project, long actor, int version) {
        return jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,?,?) RETURNING id",
                Long.class, project, version, actor);
    }

    private UUID event(long project, long baseline) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cdc_publication_inbox(event_id,project_id,baseline_id,publication_kind,status,payload)
                VALUES(?,?,?,'CHANGE','PENDING','{}'::jsonb)
                """, id, project, baseline);
        return id;
    }

    private String state(long jobId) {
        return jdbc.queryForObject("SELECT state FROM cdc_processing_job WHERE id=?", String.class, jobId);
    }

    private Claim claim(long project) {
        return processing.claim(project, Duration.ofMinutes(1)).orElseThrow();
    }

    private void makeDue(long jobId) {
        jdbc.update("UPDATE cdc_processing_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", jobId);
    }

    @Test void knownUnqueuedBaselineBlocksLaterUntilItsEventArrives() {
        var s = scenario();
        assertThat(processing.initialize(s.projectId, s.first)).isEqualTo(Initialization.INITIALIZED);
        UUID later = event(s.projectId, s.third);
        assertThat(processing.enqueue(s.projectId, s.third, later)).isTrue();
        assertThat(processing.enqueue(s.projectId, s.third, later)).isFalse();
        Claim first = claim(s.projectId);
        assertThat(first.baselineId()).isEqualTo(s.first);
        assertThat(processing.succeed(first)).isTrue();
        assertThat(processing.claim(s.projectId, Duration.ofSeconds(10))).isEmpty();

        UUID earlier = event(s.projectId, s.second);
        assertThat(processing.enqueue(s.projectId, s.second, earlier)).isTrue();
        Claim second = claim(s.projectId);
        assertThat(second.baselineId()).isEqualTo(s.second);
        assertThat(processing.succeed(second)).isTrue();
        assertThat(claim(s.projectId).baselineId()).isEqualTo(s.third);
    }

    @Test void retriesThreeTimesThenPreservesFailureAndBlocksLaterUntilManualRetry() {
        var s = scenario();
        processing.initialize(s.projectId, s.first);
        processing.enqueue(s.projectId, s.second, event(s.projectId, s.second));
        Claim first = claim(s.projectId);
        long job = first.jobId();
        for (int attempt = 1; attempt <= 4; attempt++) {
            Claim current = attempt == 1 ? first : claim(s.projectId);
            assertThat(current.attempt()).isEqualTo(attempt);
            assertThat(processing.fail(current, "handler_unavailable")).isTrue();
            assertThat(state(job)).isEqualTo(attempt == 4 ? "FAILED" : "RETRY_WAIT");
            assertThat(processing.claim(s.projectId, Duration.ofSeconds(10))).isEmpty();
            if (attempt < 4) {
                double seconds = jdbc.queryForObject("SELECT extract(epoch from next_attempt_at-updated_at) FROM cdc_processing_job WHERE id=?", Double.class, job);
                assertThat(seconds).isBetween((double) new int[]{1, 5, 30}[attempt - 1] - .1,
                        (double) new int[]{1, 5, 30}[attempt - 1] + .1);
                makeDue(job);
            }
        }
        assertThat(processing.retryFailed(s.projectId, job)).isTrue();
        Claim retry = claim(s.projectId);
        assertThat(retry.attempt()).isEqualTo(1);
        assertThat(processing.succeed(retry)).isTrue();
        assertThat(claim(s.projectId).baselineId()).isEqualTo(s.second);
    }

    @Test void expiredLeaseCannotFinalizeAndRecoveryUsesRetryBudget() {
        var s = scenario();
        processing.initialize(s.projectId, s.first);
        Claim old = claim(s.projectId);
        jdbc.update("UPDATE cdc_processing_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", old.jobId());
        assertThat(processing.succeed(old)).isFalse();
        assertThat(processing.claim(s.projectId, Duration.ofSeconds(10))).isEmpty();
        assertThat(state(old.jobId())).isEqualTo("RETRY_WAIT");
        assertThat(jdbc.queryForObject("SELECT last_error_code FROM cdc_processing_job WHERE id=?", String.class, old.jobId())).isEqualTo("lease_expired");
        makeDue(old.jobId());
        Claim fresh = claim(s.projectId);
        assertThat(fresh.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThat(processing.fail(old, "late_worker")).isFalse();
        assertThat(processing.succeed(fresh)).isTrue();
    }

    @Test void concurrentClaimsOnlyOneAndOtherProjectsProceedIndependently() throws Exception {
        var s = scenario();
        var other = scenario();
        processing.initialize(s.projectId, s.first);
        processing.initialize(other.projectId, other.first);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return processing.claim(s.projectId, Duration.ofSeconds(30)); });
            var b = pool.submit(() -> { start.await(); return processing.claim(s.projectId, Duration.ofSeconds(30)); });
            start.countDown();
            assertThat((a.get(20, TimeUnit.SECONDS).isPresent() ? 1 : 0) + (b.get(20, TimeUnit.SECONDS).isPresent() ? 1 : 0)).isEqualTo(1);
        }
        assertThat(claim(other.projectId).baselineId()).isEqualTo(other.first);
    }

    @Test void earlierEventBeforeBootstrapIsProcessedBeforeBootstrapAndForeignEventIsRejected() {
        var s = scenario();
        var other = scenario();
        UUID oldEvent = event(s.projectId, s.first);
        assertThatThrownBy(() -> processing.enqueue(other.projectId, other.first, oldEvent)).isInstanceOf(CdcEventException.class);
        processing.enqueue(s.projectId, s.first, oldEvent);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", s.second, s.projectId);
        assertThat(processing.initialize(s.projectId, s.second)).isEqualTo(Initialization.INITIALIZED);
        Claim earlier = claim(s.projectId);
        assertThat(earlier.baselineId()).isEqualTo(s.first);
        assertThat(processing.succeed(earlier)).isTrue();
        assertThat(claim(s.projectId).baselineId()).isEqualTo(s.second);
    }

    @Test void lateNewBaselineIsDurablyHeldForReconciliationWithoutSilentlyProceeding() {
        var project = fixtures().projectWithLeader();
        long first = baseline(project.projectId(), project.leaderAccountId(), 1);
        baseline(project.projectId(), project.leaderAccountId(), 2);
        long third = baseline(project.projectId(), project.leaderAccountId(), 3);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", third, project.projectId());
        processing.initialize(project.projectId(), third);
        assertThat(processing.succeed(claim(project.projectId()))).isTrue();
        assertThat(processing.enqueue(project.projectId(), first, event(project.projectId(), first))).isTrue();
        assertThat(jdbc.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", String.class, project.projectId(), first)).isEqualTo("RECONCILE");
        assertThat(jdbc.queryForObject("SELECT blocked_reason FROM cdc_processing_cursor WHERE project_id=?", String.class, project.projectId())).isEqualTo("late_baseline");
        assertThat(processing.claim(project.projectId(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test void aLateEarlierBaselineWhileRunningPreventsSuccessAndBlocksTheCursor() {
        var project = fixtures().projectWithLeader();
        long first = baseline(project.projectId(), project.leaderAccountId(), 1);
        baseline(project.projectId(), project.leaderAccountId(), 2);
        long third = baseline(project.projectId(), project.leaderAccountId(), 3);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", third, project.projectId());
        processing.initialize(project.projectId(), third);
        Claim thirdClaim = claim(project.projectId());
        processing.enqueue(project.projectId(), first, event(project.projectId(), first));
        assertThat(processing.succeed(thirdClaim)).isFalse();
        assertThat(jdbc.queryForObject("SELECT blocked_reason FROM cdc_processing_cursor WHERE project_id=?", String.class, project.projectId())).isEqualTo("late_baseline");
        assertThat(processing.claim(project.projectId(), Duration.ofSeconds(5))).isEmpty();
    }

    @Test void missingIntermediateVersionDoesNotAdvanceToLaterBaseline() {
        var project = fixtures().projectWithLeader();
        long first = baseline(project.projectId(), project.leaderAccountId(), 1);
        long third = baseline(project.projectId(), project.leaderAccountId(), 3);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", first, project.projectId());
        processing.initialize(project.projectId(), first);
        processing.enqueue(project.projectId(), third, event(project.projectId(), third));
        assertThat(processing.succeed(claim(project.projectId()))).isTrue();
        assertThat(processing.claim(project.projectId(), Duration.ofSeconds(5))).isEmpty();
        long second = baseline(project.projectId(), project.leaderAccountId(), 2);
        processing.enqueue(project.projectId(), second, event(project.projectId(), second));
        assertThat(processing.succeed(claim(project.projectId()))).isTrue();
        assertThat(claim(project.projectId()).baselineId()).isEqualTo(third);
    }

    @Test void bootstrapJobLinksFirstLiveEventAndKeepsOtherReceipts() {
        var s = scenario();
        processing.initialize(s.projectId, s.first);
        UUID firstEvent = event(s.projectId, s.first);
        UUID secondEvent = event(s.projectId, s.first);
        assertThat(processing.enqueue(s.projectId, s.first, firstEvent)).isFalse();
        assertThat(processing.enqueue(s.projectId, s.first, secondEvent)).isFalse();
        assertThat(jdbc.queryForObject("SELECT source_event_id FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", UUID.class, s.projectId, s.first)).isEqualTo(firstEvent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE project_id=? AND baseline_id=?", Integer.class, s.projectId, s.first)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", Integer.class, s.projectId, s.first)).isEqualTo(1);
    }
}
