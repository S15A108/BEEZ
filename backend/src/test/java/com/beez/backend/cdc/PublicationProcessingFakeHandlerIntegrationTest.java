package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.cdc.PublicationProcessingService.Claim;
import com.beez.backend.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicit test poll loop only. No production scheduler or impact handler is wired. */
class PublicationProcessingFakeHandlerIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationProcessingService processing;

    private enum HandlerResult { SUCCESS, RETRYABLE_FAILURE }
    private enum PollResult { NOT_DUE, SUCCEEDED, FAILED_ATTEMPT }

    private static final class FakeHandler {
        private final Queue<HandlerResult> results;
        private final List<Long> handledBaselines = new ArrayList<>();

        FakeHandler(HandlerResult... results) {
            this.results = new ArrayDeque<>(List.of(results));
        }

        HandlerResult handle(Claim claim) {
            handledBaselines.add(claim.baselineId());
            HandlerResult result = results.poll();
            if (result == null) throw new AssertionError("Unexpected handler call");
            return result;
        }
    }

    private PollResult pollOnce(long projectId, FakeHandler handler) {
        var claimed = processing.claim(projectId, Duration.ofSeconds(30));
        if (claimed.isEmpty()) return PollResult.NOT_DUE;
        Claim claim = claimed.orElseThrow();
        if (handler.handle(claim) == HandlerResult.SUCCESS) {
            assertThat(processing.succeed(claim)).isTrue();
            return PollResult.SUCCEEDED;
        }
        assertThat(processing.fail(claim, "fake_handler_unavailable")).isTrue();
        return PollResult.FAILED_ATTEMPT;
    }

    private long newBaseline(long projectId, long actorId, int version) {
        return jdbc.queryForObject(
                "INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,?,?) RETURNING id",
                Long.class, projectId, version, actorId);
    }

    private UUID newInboxEvent(long projectId, long baselineId) {
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cdc_publication_inbox(event_id,project_id,baseline_id,publication_kind,status,payload)
                VALUES(?,?,?,'CHANGE','PENDING','{}'::jsonb)
                """, eventId, projectId, baselineId);
        return eventId;
    }

    private String state(long projectId, long baselineId) {
        return jdbc.queryForObject(
                "SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",
                String.class, projectId, baselineId);
    }

    private long jobId(long projectId, long baselineId) {
        return jdbc.queryForObject(
                "SELECT id FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",
                Long.class, projectId, baselineId);
    }

    private double retryDelay(long jobId) {
        return jdbc.queryForObject(
                "SELECT extract(epoch from next_attempt_at-updated_at) FROM cdc_processing_job WHERE id=?",
                Double.class, jobId);
    }

    private void accelerateRetryForTest(long jobId) {
        // Test-only clock acceleration: production must wait until next_attempt_at.
        jdbc.update("UPDATE cdc_processing_job SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", jobId);
    }

    @Test void transientFailuresWaitForBackoffThenTestHandlerSucceeds() {
        var project = fixtures().projectWithLeader();
        long baseline = newBaseline(project.projectId(), project.leaderAccountId(), 1);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        processing.initialize(project.projectId(), baseline);
        long jobId = jobId(project.projectId(), baseline);
        var handler = new FakeHandler(HandlerResult.RETRYABLE_FAILURE, HandlerResult.RETRYABLE_FAILURE,
                HandlerResult.RETRYABLE_FAILURE, HandlerResult.SUCCESS);

        for (int expectedDelay : new int[]{1, 5, 30}) {
            assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.FAILED_ATTEMPT);
            assertThat(state(project.projectId(), baseline)).isEqualTo("RETRY_WAIT");
            assertThat(retryDelay(jobId)).isBetween(expectedDelay - 0.1, expectedDelay + 0.1);
            assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.NOT_DUE);
            accelerateRetryForTest(jobId);
        }
        assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.SUCCEEDED);
        assertThat(state(project.projectId(), baseline)).isEqualTo("SUCCEEDED");
        assertThat(handler.handledBaselines).containsExactly(baseline, baseline, baseline, baseline);
        assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.NOT_DUE);
    }

    @Test void exhaustedRetriesKeepFailureAndLaterVersionWaitingUntilExplicitRetry() {
        var project = fixtures().projectWithLeader();
        long first = newBaseline(project.projectId(), project.leaderAccountId(), 1);
        long second = newBaseline(project.projectId(), project.leaderAccountId(), 2);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", first, project.projectId());
        processing.initialize(project.projectId(), first);
        processing.enqueue(project.projectId(), second, newInboxEvent(project.projectId(), second));
        long firstJob = jobId(project.projectId(), first);
        var handler = new FakeHandler(HandlerResult.RETRYABLE_FAILURE, HandlerResult.RETRYABLE_FAILURE,
                HandlerResult.RETRYABLE_FAILURE, HandlerResult.RETRYABLE_FAILURE,
                HandlerResult.SUCCESS, HandlerResult.SUCCESS);

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.FAILED_ATTEMPT);
            if (attempt < 4) accelerateRetryForTest(firstJob);
        }
        assertThat(state(project.projectId(), first)).isEqualTo("FAILED");
        assertThat(state(project.projectId(), second)).isEqualTo("WAITING");
        assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.NOT_DUE);
        assertThat(handler.handledBaselines).containsExactly(first, first, first, first);

        assertThat(processing.retryFailed(project.projectId(), firstJob)).isTrue();
        assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.SUCCEEDED);
        assertThat(pollOnce(project.projectId(), handler)).isEqualTo(PollResult.SUCCEEDED);
        assertThat(handler.handledBaselines).containsExactly(first, first, first, first, first, second);
        assertThat(state(project.projectId(), first)).isEqualTo("SUCCEEDED");
        assertThat(state(project.projectId(), second)).isEqualTo("SUCCEEDED");
    }
}
