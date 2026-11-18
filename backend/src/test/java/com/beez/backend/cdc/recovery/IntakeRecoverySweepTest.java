package com.beez.backend.cdc.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IntakeRecoverySweepTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private final IntakeRecoveryScanPort source = mock(IntakeRecoveryScanPort.class);
    private final AnalysisIntakeCheckPort check = mock(AnalysisIntakeCheckPort.class);
    private final IntakeRecoverySweep sweep = new IntakeRecoverySweep(source, check);

    private static IntakeRecoveryCandidate candidate(String reference, IntakeRecoveryCandidate.Kind kind) {
        return new IntakeRecoveryCandidate(reference, kind);
    }

    @Test void visitsPendingAndExpiredOncePerPageAndReturnsContinuation() {
        var pending = candidate("p", IntakeRecoveryCandidate.Kind.PENDING);
        var expired = candidate("r", IntakeRecoveryCandidate.Kind.EXPIRED_RUNNING);
        when(source.findCandidates(NOW, 3, null))
                .thenReturn(new IntakeRecoveryScanPort.Page(List.of(pending, expired, pending), "next"));
        assertThat(sweep.runPage(NOW, 3, null)).isEqualTo(new IntakeRecoverySweep.Result(2, 0, "next"));
        verify(check, times(1)).ensureSafeToAcknowledge("p");
        verify(check, times(1)).ensureSafeToAcknowledge("r");
    }

    @Test void failureIsCountedAndDoesNotStopOtherRowsOrLeakError() {
        var a = candidate("sensitive-id", IntakeRecoveryCandidate.Kind.PENDING);
        var b = candidate("b", IntakeRecoveryCandidate.Kind.EXPIRED_RUNNING);
        when(source.findCandidates(NOW, 3, null))
                .thenReturn(new IntakeRecoveryScanPort.Page(List.of(a, a, b), null));
        doThrow(new IllegalStateException("sensitive-error")).when(check).ensureSafeToAcknowledge("sensitive-id");
        var result = sweep.runPage(NOW, 3, null);
        assertThat(result.considered()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("sensitive-id", "sensitive-error");
        verify(check).ensureSafeToAcknowledge("b");
        verify(check, times(1)).ensureSafeToAcknowledge("sensitive-id");
    }

    @Test void aLaterSweepRechecksTheSamePersistentCandidate() {
        var c = candidate("p", IntakeRecoveryCandidate.Kind.PENDING);
        when(source.findCandidates(NOW, 1, null)).thenReturn(new IntakeRecoveryScanPort.Page(List.of(c), null));
        doThrow(new IllegalStateException("first attempt"))
                .doNothing().when(check).ensureSafeToAcknowledge("p");
        assertThat(sweep.runPage(NOW, 1, null).failed()).isEqualTo(1);
        assertThat(sweep.runPage(NOW, 1, null).failed()).isZero();
        verify(check, times(2)).ensureSafeToAcknowledge("p");
    }

    @Test void scanFailureAndMalformedPagesNeverDispatch() {
        when(source.findCandidates(NOW, 1, null)).thenThrow(new IllegalStateException("scan failed"));
        assertThatThrownBy(() -> sweep.runPage(NOW, 1, null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(check);
        var c = candidate("p", IntakeRecoveryCandidate.Kind.PENDING);
        doReturn(new IntakeRecoveryScanPort.Page(List.of(c, c), null)).when(source).findCandidates(NOW, 1, null);
        assertThatThrownBy(() -> sweep.runPage(NOW, 1, null)).hasMessageContaining("page limit");
        verifyNoInteractions(check);
    }

    @Test void stuckCursorIsRejectedBeforeDispatch() {
        var c = candidate("p", IntakeRecoveryCandidate.Kind.PENDING);
        when(source.findCandidates(NOW, 1, "cursor"))
                .thenReturn(new IntakeRecoveryScanPort.Page(List.of(c), "cursor"));
        assertThatThrownBy(() -> sweep.runPage(NOW, 1, "cursor"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("did not advance");
        verifyNoInteractions(check);
    }

    @Test void validatesBoundsAndCandidateDataWithoutReadingDb() {
        assertThatThrownBy(() -> sweep.runPage(null, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> sweep.runPage(NOW, 0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sweep.runPage(NOW, 501, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sweep.runPage(NOW, 1, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(" ", IntakeRecoveryCandidate.Kind.PENDING)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate("id", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntakeRecoveryScanPort.Page(List.of(), " ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(source, check);
    }

    @Test void concurrentSweepsRelyOnTheAdaptersAtomicClaim() throws Exception {
        var c = candidate("p", IntakeRecoveryCandidate.Kind.EXPIRED_RUNNING);
        var page = new IntakeRecoveryScanPort.Page(List.of(c), null);
        var rendezvous = new CountDownLatch(2);
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        var actualStarts = new AtomicInteger();
        AnalysisIntakeCheckPort atomicAdapter = reference -> {
            rendezvous.countDown();
            try {
                if (!rendezvous.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test rendezvous failed");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            if (claimed.add(reference)) actualStarts.incrementAndGet();
        };
        var concurrentSweep = new IntakeRecoverySweep((now, limit, cursor) -> page, atomicAdapter);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentSweep.runPage(NOW, 1, null));
            var second = executor.submit(() -> concurrentSweep.runPage(NOW, 1, null));
            assertThat(first.get(10, TimeUnit.SECONDS).failed()).isZero();
            assertThat(second.get(10, TimeUnit.SECONDS).failed()).isZero();
        }
        // The fake adapter enforces the claim; the sweep itself does not guarantee it.
        assertThat(actualStarts.get()).isEqualTo(1);
    }
}
