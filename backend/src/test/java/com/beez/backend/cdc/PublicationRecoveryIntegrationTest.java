package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.cdc.PublicationProcessingService.Claim;
import com.beez.backend.cdc.PublicationProcessingService.RecoveryResult;
import com.beez.backend.support.IntegrationTestSupport;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PublicationRecoveryIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired PublicationProcessingService processing;

    private record Scenario(long project, long first, long second, long third) {}

    private Scenario scenario() {
        var project = fixtures().projectWithLeader();
        long first = baseline(project.projectId(), project.leaderAccountId(), 1);
        long second = baseline(project.projectId(), project.leaderAccountId(), 2);
        long third = baseline(project.projectId(), project.leaderAccountId(), 3);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", third, project.projectId());
        processing.initialize(project.projectId(), third);
        return new Scenario(project.projectId(), first, second, third);
    }

    private long baseline(long project, long actor, int version) {
        return jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,?,?) RETURNING id",
                Long.class, project, version, actor);
    }

    private void receive(long project, long baseline) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cdc_publication_inbox(event_id,project_id,baseline_id,publication_kind,status,payload)
                VALUES(?,?,?,'CHANGE','PENDING','{}'::jsonb)
                """, id, project, baseline);
        processing.enqueue(project, baseline, id);
    }

    private Claim claim(long project) {
        return processing.claim(project, Duration.ofMinutes(1)).orElseThrow();
    }

    private String state(long project, long baseline) {
        return jdbc.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",
                String.class, project, baseline);
    }

    private String blocked(long project) {
        return jdbc.queryForObject("SELECT blocked_reason FROM cdc_processing_cursor WHERE project_id=?",
                String.class, project);
    }

    @Test void rewindOnlyUnattemptedJobsAndResumeInOrderWithoutDroppingEvents() {
        var s = scenario();
        receive(s.project, s.second);
        receive(s.project, s.first);
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.RECOVERED);
        assertThat(blocked(s.project)).isNull();
        assertThat(state(s.project, s.first)).isEqualTo("WAITING");
        assertThat(state(s.project, s.second)).isEqualTo("WAITING");
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.NOT_BLOCKED);
        Claim first = claim(s.project);
        assertThat(first.baselineId()).isEqualTo(s.first);
        assertThat(processing.succeed(first)).isTrue();
        Claim second = claim(s.project);
        assertThat(second.baselineId()).isEqualTo(s.second);
        assertThat(processing.succeed(second)).isTrue();
        assertThat(claim(s.project).baselineId()).isEqualTo(s.third);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE project_id=?",
                Integer.class, s.project)).isEqualTo(2);
    }

    @Test void completedLaterWorkRequiresManualRebuildAndPreservesHistory() {
        var s = scenario();
        assertThat(processing.succeed(claim(s.project))).isTrue();
        receive(s.project, s.first);
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.MANUAL_REBUILD_REQUIRED);
        assertThat(state(s.project, s.first)).isEqualTo("RECONCILE");
        assertThat(state(s.project, s.third)).isEqualTo("SUCCEEDED");
        assertThat(blocked(s.project)).isEqualTo("late_baseline");
        assertThat(processing.claim(s.project, Duration.ofSeconds(10))).isEmpty();
    }

    @Test void activeOrExpiredLeaseNeverAutomaticallyRewinds() {
        var s = scenario();
        Claim third = claim(s.project);
        receive(s.project, s.first);
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.LEASE_IN_PROGRESS);
        jdbc.update("UPDATE cdc_processing_job SET lease_until=clock_timestamp()-interval '1 second' WHERE id=?", third.jobId());
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.MANUAL_REBUILD_REQUIRED);
        assertThat(processing.succeed(third)).isFalse();
        assertThat(processing.fail(third, "expired_worker")).isFalse();
        assertThat(processing.claim(s.project, Duration.ofSeconds(10))).isEmpty();
        assertThat(state(s.project, s.third)).isEqualTo("RUNNING");
        assertThat(state(s.project, s.first)).isEqualTo("RECONCILE");
        assertThat(blocked(s.project)).isEqualTo("late_baseline");
    }

    @Test void failedLaterAttemptMayHaveExternalEffectsAndRequiresManualReview() {
        var s = scenario();
        Claim third = claim(s.project);
        assertThat(processing.fail(third, "external_unavailable")).isTrue();
        receive(s.project, s.first);
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.MANUAL_REBUILD_REQUIRED);
        assertThat(state(s.project, s.third)).isEqualTo("RETRY_WAIT");
        assertThat(state(s.project, s.first)).isEqualTo("RECONCILE");
    }

    @Test void concurrentRecoveryIsSerializedAndLeavesOneCursor() throws Exception {
        var s = scenario();
        receive(s.project, s.first);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return processing.recoverUnattemptedReordering(s.project); });
            var b = pool.submit(() -> { start.await(); return processing.recoverUnattemptedReordering(s.project); });
            start.countDown();
            assertThat(java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(RecoveryResult.RECOVERED, RecoveryResult.NOT_BLOCKED);
        }
        assertThat(claim(s.project).baselineId()).isEqualTo(s.first);
    }

    @Test void noCursorOrMissingTargetDoesNotInventARecovery() {
        var noCursor = fixtures().projectWithLeader();
        assertThat(processing.recoverUnattemptedReordering(noCursor.projectId())).isEqualTo(RecoveryResult.NOT_INITIALIZED);
        var s = scenario();
        jdbc.update("UPDATE cdc_processing_cursor SET blocked_reason='baseline_reordered', next_version=2 WHERE project_id=?", s.project);
        assertThat(processing.recoverUnattemptedReordering(s.project)).isEqualTo(RecoveryResult.NO_RECOVERY_TARGET);
        assertThat(blocked(s.project)).isEqualTo("baseline_reordered");
    }
}
