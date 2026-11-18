package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.support.IntegrationTestSupport;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PublicationBootstrapIntegrationTest extends IntegrationTestSupport {
    @Autowired JdbcTemplate db;
    @Autowired PublicationBootstrapService bootstrap;
    @Autowired PublicationProcessingService processing;
    @Autowired PublicationInboxService inbox;
    @Autowired PublicationEventParser parser;
    @Autowired PlatformTransactionManager transactionManager;

    private long publishedBaseline(long project, long actor, int version) {
        return db.queryForObject(
                "INSERT INTO baseline(project_id, version_no, published_by) VALUES (?, ?, ?) RETURNING id",
                Long.class, project, version, actor);
    }

    private void setCurrent(long project, long baseline) {
        db.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project);
    }

    private int jobs(long project, long baseline) {
        return db.queryForObject("""
                SELECT count(*) FROM cdc_processing_job
                WHERE project_id=? AND baseline_id=? AND job_type='PUBLISHED_SYNC'
                """, Integer.class, project, baseline);
    }

    @Test void pageReturnsOnlyCurrentPublishedBaselinesAndNoContent() {
        var first = fixtures().projectWithLeader();
        var withoutBaseline = fixtures().projectWithLeader();
        var last = fixtures().projectWithLeader();
        long firstBaseline = publishedBaseline(first.projectId(), first.leaderAccountId(), 1);
        long lastBaseline = publishedBaseline(last.projectId(), last.leaderAccountId(), 1);
        setCurrent(first.projectId(), firstBaseline);
        setCurrent(last.projectId(), lastBaseline);

        var firstPage = bootstrap.candidatesAfter(first.projectId() - 1, 1);
        assertThat(firstPage).containsExactly(new PublicationBootstrapService.Candidate(first.projectId(), firstBaseline, 1));
        var nextPage = bootstrap.candidatesAfter(firstPage.getFirst().projectId(), 1);
        assertThat(nextPage).containsExactly(new PublicationBootstrapService.Candidate(last.projectId(), lastBaseline, 1));
        assertThat(bootstrap.initializeCurrent(withoutBaseline.projectId()))
                .isEqualTo(PublicationBootstrapService.Initialization.NO_PUBLISHED_BASELINE);
        assertThat(bootstrap.initializeCurrent(Long.MAX_VALUE))
                .isEqualTo(PublicationBootstrapService.Initialization.PROJECT_NOT_FOUND);
        assertThatThrownBy(() -> bootstrap.candidatesAfter(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap.candidatesAfter(0, 501)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void staleScannedCandidateIsRereadBeforeInitialization() {
        var project = fixtures().projectWithLeader();
        long oldBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), oldBaseline);
        var scanned = bootstrap.candidatesAfter(project.projectId() - 1, 1).getFirst();
        assertThat(scanned.baselineId()).isEqualTo(oldBaseline);

        long newBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
        setCurrent(project.projectId(), newBaseline);
        assertThat(bootstrap.initializeCurrent(project.projectId()))
                .isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
        assertThat(jobs(project.projectId(), oldBaseline)).isZero();
        assertThat(jobs(project.projectId(), newBaseline)).isEqualTo(1);
        assertThat(bootstrap.initializeCurrent(project.projectId()))
                .isEqualTo(PublicationBootstrapService.Initialization.ALREADY_INITIALIZED);
        assertThat(jobs(project.projectId(), newBaseline)).isEqualTo(1);
        // This fixture models a DB write only; a live CDC checkpoint is not tested here.
        assertThat(db.queryForObject("SELECT count(*) FROM publication_outbox WHERE baseline_id=?", Integer.class, newBaseline)).isZero();
    }

    @Test void bootstrapAndRedeliveredLiveEventShareOneBaselineJob() {
        var project = fixtures().projectWithLeader();
        long baseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), baseline);
        assertThat(bootstrap.initializeCurrent(project.projectId()))
                .isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);

        UUID eventId = UUID.randomUUID();
        var event = parser.parse(PublicationEventParserTest.envelope(eventId, "INITIAL", project.projectId(), baseline, false))
                .orElseThrow();
        inbox.accept(event);
        inbox.accept(event);
        assertThat(jobs(project.projectId(), baseline)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM cdc_publication_inbox WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",
                String.class, project.projectId(), baseline)).isEqualTo("WAITING");
    }

    @Test void earlierReceivedLiveWorkIsNotSilentlySkippedByBootstrap() {
        var project = fixtures().projectWithLeader();
        long earlier = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        long current = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
        setCurrent(project.projectId(), current);
        UUID eventId = UUID.randomUUID();
        inbox.accept(parser.parse(PublicationEventParserTest.envelope(eventId, "INITIAL", project.projectId(), earlier, false)).orElseThrow());
        assertThat(bootstrap.initializeCurrent(project.projectId()))
                .isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
        assertThat(jobs(project.projectId(), earlier)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?",
                String.class, project.projectId(), earlier)).isEqualTo("WAITING");
        assertThat(jobs(project.projectId(), current)).isEqualTo(1);
        var first = processing.claim(project.projectId(), java.time.Duration.ofSeconds(30)).orElseThrow();
        assertThat(first.baselineId()).isEqualTo(earlier);
        assertThat(processing.claim(project.projectId(), java.time.Duration.ofSeconds(30))).isEmpty();
        // No production handler is wired: neither baseline is claimed as successfully synchronized.
    }

    @Test void concurrentPublicationIsReadAfterProjectLockIsReleased() throws Exception {
        var project = fixtures().projectWithLeader();
        long oldBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), oldBaseline);
        var updated = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        var startingBootstrap = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var publishing = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                db.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", Long.class, project.projectId());
                long next = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
                setCurrent(project.projectId(), next);
                updated.countDown();
                try {
                    if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test commit timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return next;
            }));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            var initialising = pool.submit(() -> {
                startingBootstrap.countDown();
                return bootstrap.initializeCurrent(project.projectId());
            });
            assertThat(startingBootstrap.await(10, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(() -> initialising.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                commit.countDown();
            }
            long published = publishing.get(20, TimeUnit.SECONDS);
            assertThat(initialising.get(20, TimeUnit.SECONDS)).isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
            assertThat(jobs(project.projectId(), oldBaseline)).isZero();
            assertThat(jobs(project.projectId(), published)).isEqualTo(1);
            // No Kafka startup/checkpoint is exercised; this only verifies the DB lock boundary.
        }
    }
    @Test void scannedBaselineSurvivesLaterPublicationAndLiveEvent() {
        var project = fixtures().projectWithLeader();
        long oldBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), oldBaseline);
        var scanned = bootstrap.candidatesAfter(project.projectId() - 1, 1).getFirst();
        long current = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
        setCurrent(project.projectId(), current);
        UUID eventId = UUID.randomUUID();
        var event = parser.parse(PublicationEventParserTest.envelope(eventId, "CHANGE", project.projectId(), current, false)).orElseThrow();
        // Live event reaches the queue before the scanned page is initialized.
        inbox.accept(event);
        assertThat(bootstrap.initializeScannedCandidate(scanned))
                .isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
        inbox.accept(event);
        assertThat(jobs(project.projectId(), oldBaseline)).isEqualTo(1);
        assertThat(jobs(project.projectId(), current)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM publication_outbox WHERE baseline_id=?", Integer.class, oldBaseline)).isZero();
        assertThat(db.queryForObject("SELECT next_version FROM cdc_processing_cursor WHERE project_id=?", Long.class, project.projectId())).isEqualTo(1);
        assertThat(processing.claim(project.projectId(), java.time.Duration.ofSeconds(30)).orElseThrow().baselineId()).isEqualTo(oldBaseline);
    }

    @Test void existingCursorIsNeverRewoundAndUnrepresentedCandidateRequiresReconciliation() {
        var project = fixtures().projectWithLeader();
        long oldBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), oldBaseline);
        var scanned = bootstrap.candidatesAfter(project.projectId() - 1, 1).getFirst();
        long current = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
        setCurrent(project.projectId(), current);
        assertThat(bootstrap.initializeCurrent(project.projectId())).isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
        var claim = processing.claim(project.projectId(), java.time.Duration.ofSeconds(30)).orElseThrow();
        assertThat(processing.succeed(claim)).isTrue();
        assertThat(bootstrap.initializeScannedCandidate(scanned)).isEqualTo(PublicationBootstrapService.Initialization.RECONCILIATION_REQUIRED);
        assertThat(jobs(project.projectId(), oldBaseline)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", String.class, project.projectId(), oldBaseline)).isEqualTo("RECONCILE");
        assertThat(db.queryForObject("SELECT blocked_reason FROM cdc_processing_cursor WHERE project_id=?", String.class, project.projectId())).isEqualTo("late_baseline");
        assertThat(db.queryForObject("SELECT count(*) FROM publication_outbox WHERE baseline_id=?", Integer.class, oldBaseline)).isZero();
        assertThat(processing.claim(project.projectId(), java.time.Duration.ofSeconds(30))).isEmpty();
        assertThat(processing.recoverUnattemptedReordering(project.projectId())).isEqualTo(PublicationProcessingService.RecoveryResult.MANUAL_REBUILD_REQUIRED);
        assertThat(db.queryForObject("SELECT next_version FROM cdc_processing_cursor WHERE project_id=?", Long.class, project.projectId())).isEqualTo(3);
        assertThat(db.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", String.class, project.projectId(), current)).isEqualTo("SUCCEEDED");
        UUID lateEvent = UUID.randomUUID();
        inbox.accept(parser.parse(PublicationEventParserTest.envelope(lateEvent, "INITIAL", project.projectId(), oldBaseline, false)).orElseThrow());
        assertThat(db.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", String.class, project.projectId(), oldBaseline)).isEqualTo("RECONCILE");
        assertThat(db.queryForObject("SELECT blocked_reason FROM cdc_processing_cursor WHERE project_id=?", String.class, project.projectId())).isEqualTo("late_baseline");
        assertThat(db.queryForObject("SELECT next_version FROM cdc_processing_cursor WHERE project_id=?", Long.class, project.projectId())).isEqualTo(3);
    }

    @Test void missedScannedCandidateBlocksClaimsUntilSafeRecovery() {
        var project = fixtures().projectWithLeader();
        long oldBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), oldBaseline);
        var scanned = bootstrap.candidatesAfter(project.projectId() - 1, 1).getFirst();
        long current = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
        setCurrent(project.projectId(), current);
        assertThat(bootstrap.initializeCurrent(project.projectId())).isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
        assertThat(bootstrap.initializeScannedCandidate(scanned)).isEqualTo(PublicationBootstrapService.Initialization.RECONCILIATION_REQUIRED);
        assertThat(jobs(project.projectId(), oldBaseline)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM publication_outbox WHERE baseline_id=?", Integer.class, oldBaseline)).isZero();
        assertThat(db.queryForObject("SELECT state FROM cdc_processing_job WHERE project_id=? AND baseline_id=?", String.class, project.projectId(), oldBaseline)).isEqualTo("RECONCILE");
        assertThat(db.queryForObject("SELECT blocked_reason FROM cdc_processing_cursor WHERE project_id=?", String.class, project.projectId())).isEqualTo("late_baseline");
        assertThat(processing.claim(project.projectId(), java.time.Duration.ofSeconds(30))).isEmpty();
        assertThat(processing.recoverUnattemptedReordering(project.projectId())).isEqualTo(PublicationProcessingService.RecoveryResult.RECOVERED);
        assertThat(db.queryForObject("SELECT next_version FROM cdc_processing_cursor WHERE project_id=?", Long.class, project.projectId())).isEqualTo(1);
        assertThat(processing.claim(project.projectId(), java.time.Duration.ofSeconds(30)).orElseThrow().baselineId()).isEqualTo(oldBaseline);
    }

    @Test void candidateMustMatchItsPublishedProjectAndVersion() {
        var project = fixtures().projectWithLeader();
        var other = fixtures().projectWithLeader();
        long baseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        long otherBaseline = publishedBaseline(other.projectId(), other.leaderAccountId(), 1);
        setCurrent(project.projectId(), baseline);
        setCurrent(other.projectId(), otherBaseline);
        assertThatThrownBy(() -> bootstrap.initializeScannedCandidate(new PublicationBootstrapService.Candidate(project.projectId(), otherBaseline, 1)))
                .isInstanceOf(CdcEventException.class);
        assertThatThrownBy(() -> bootstrap.initializeScannedCandidate(new PublicationBootstrapService.Candidate(project.projectId(), baseline, 2)))
                .isInstanceOf(CdcEventException.class);
        assertThat(jobs(project.projectId(), baseline)).isZero();
    }

    @Test void scannedCandidateWaitsForConcurrentPublicationAndKeepsBothVersions() throws Exception {
        var project = fixtures().projectWithLeader();
        long oldBaseline = publishedBaseline(project.projectId(), project.leaderAccountId(), 1);
        setCurrent(project.projectId(), oldBaseline);
        var scanned = bootstrap.candidatesAfter(project.projectId() - 1, 1).getFirst();
        var updated = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var publishing = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                db.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", Long.class, project.projectId());
                long next = publishedBaseline(project.projectId(), project.leaderAccountId(), 2);
                setCurrent(project.projectId(), next);
                updated.countDown();
                try { if (!commit.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return next;
            }));
            assertThat(updated.await(10, TimeUnit.SECONDS)).isTrue();
            var initialising = pool.submit(() -> bootstrap.initializeScannedCandidate(scanned));
            try { assertThatThrownBy(() -> initialising.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { commit.countDown(); }
            long next = publishing.get(20, TimeUnit.SECONDS);
            assertThat(initialising.get(20, TimeUnit.SECONDS)).isEqualTo(PublicationBootstrapService.Initialization.INITIALIZED);
            assertThat(jobs(project.projectId(), oldBaseline)).isEqualTo(1);
            assertThat(jobs(project.projectId(), next)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT next_version FROM cdc_processing_cursor WHERE project_id=?", Long.class, project.projectId())).isEqualTo(1);
        }
    }

}
