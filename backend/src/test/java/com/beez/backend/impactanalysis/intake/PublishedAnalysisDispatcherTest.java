package com.beez.backend.impactanalysis.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import com.beez.backend.cdc.recovery.IntakeRecoveryCandidate;
import com.beez.backend.cdc.recovery.IntakeRecoveryScanPort;
import com.beez.backend.impactanalysis.PublishedAnalysisProcessor;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PublishedAnalysisDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-11-09T00:00:00Z");
    private final PublishedAnalysisRepository repository = mock(PublishedAnalysisRepository.class);
    private final PublishedAnalysisProcessor processor = mock(PublishedAnalysisProcessor.class);
    private final PublishedAnalysisDispatcher dispatcher = new PublishedAnalysisDispatcher(repository, processor);

    @AfterEach void shutdown() { dispatcher.destroy(); }

    private static IntakeRecoveryCandidate candidate(long id) {
        return new IntakeRecoveryCandidate(Long.toString(id), IntakeRecoveryCandidate.Kind.PENDING);
    }

    @Test void duplicateHintsDuringProcessingAreCheckedButStartOnlyOneLocalWorker() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("worker was not released");
            return null;
        }).when(processor).process(7L);
        try {
            dispatcher.ensureSafeToAcknowledge("7");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.ensureSafeToAcknowledge("7");
            verify(repository, times(2)).ensureSafeToAcknowledge("7");
            verify(processor, times(1)).process(7L);
        } finally {
            release.countDown();
        }
        // This only asserts local in-flight suppression, not exactly-once external calls.
    }

    @Test void aFailedDurabilityCheckDoesNotDispatchOrAcknowledgeTheHint() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).ensureSafeToAcknowledge("7");
        assertThatThrownBy(() -> dispatcher.ensureSafeToAcknowledge("7"))
                .isInstanceOf(IllegalStateException.class);
        verify(processor, never()).process(anyLong());
    }

    @Test void failedWorkerCanBeDispatchedAgainByARecoveryScan() throws Exception {
        var failed = new CountDownLatch(1);
        var retried = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                failed.countDown();
                throw new IllegalStateException("temporary processing failure");
            }
            retried.countDown();
            return null;
        }).when(processor).process(7L);
        when(repository.databaseTime()).thenReturn(NOW);
        when(repository.findCandidates(NOW, 32, null))
                .thenReturn(new IntakeRecoveryScanPort.Page(List.of(candidate(7)), null));

        dispatcher.ensureSafeToAcknowledge("7");
        assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        // The first worker may still be unwinding. Repeated scans model the scheduler.
        while (retried.getCount() != 0 && System.nanoTime() < deadline) {
            dispatcher.recover();
            retried.await(20, TimeUnit.MILLISECONDS);
        }
        assertThat(retried.getCount()).isZero();
        verify(processor, times(2)).process(7L);
    }

    @Test void fullQueueResumesBeforeRejectedCandidateAndLaterRecoveryProcessesIt() throws Exception {
        var workersStarted = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var initialDone = new CountDownLatch(10);
        var remainingDone = new CountDownLatch(2);
        doAnswer(call -> {
            long id = call.getArgument(0);
            if (id <= 10) {
                workersStarted.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("workers were not released");
                initialDone.countDown();
            } else {
                remainingDone.countDown();
            }
            return null;
        }).when(processor).process(anyLong());
        var first = LongStream.rangeClosed(1, 12).mapToObj(PublishedAnalysisDispatcherTest::candidate).toList();
        var remaining = List.of(candidate(11), candidate(12));
        when(repository.databaseTime()).thenReturn(NOW);
        when(repository.findCandidates(NOW, 32, null))
                .thenReturn(new IntakeRecoveryScanPort.Page(first, null));
        when(repository.findCandidates(NOW, 32, "10"))
                .thenReturn(new IntakeRecoveryScanPort.Page(remaining, null));
        try {
            dispatcher.recover();
            assertThat(workersStarted.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.recover(); // Still full: retry from the last accepted row, not the end of the page.
            verify(repository).findCandidates(NOW, 32, "10");
            verify(processor, never()).process(11L);
            release.countDown();
            assertThat(initialDone.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.recover();
            assertThat(remainingDone.await(5, TimeUnit.SECONDS)).isTrue();
            verify(processor, times(1)).process(11L);
            verify(processor, times(1)).process(12L);
        } finally {
            release.countDown();
        }
    }
}
