package com.beez.backend.cdc.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnalysisIntakeSignalHandlerTest {
    @Test void pendingInsertSnapshotAndUpdateAskAdapterToCheckCurrentState() {
        var calls = new java.util.ArrayList<String>();
        var handler = new AnalysisIntakeSignalHandler(calls::add);
        for (String operation : List.of("c", "r", "u")) {
            assertThat(handler.handle(new AnalysisIntakeChange("opaque-ref", operation, "PENDING")))
                    .isEqualTo(AnalysisIntakeSignalHandler.Result.CHECK_ACCEPTED);
        }
        assertThat(calls).containsExactly("opaque-ref", "opaque-ref", "opaque-ref");
    }

    @Test void ignoredStatusesAndTrueTombstoneDoNotCallAdapter() {
        var calls = new AtomicInteger();
        var handler = new AnalysisIntakeSignalHandler(ref -> calls.incrementAndGet());
        for (String status : List.of("NOT_EVALUATED", "RUNNING", "COMPLETED", "FAILED")) {
            assertThat(handler.handle(new AnalysisIntakeChange("id", "r", status)))
                    .isEqualTo(AnalysisIntakeSignalHandler.Result.IGNORED);
        }
        assertThatThrownBy(() -> handler.handle(new AnalysisIntakeChange(null, "d", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(handler.handleTombstone()).isEqualTo(AnalysisIntakeSignalHandler.Result.IGNORED);
        assertThat(calls).hasValue(0);
    }

    @Test void repeatedDeliveryAlwaysRechecksCurrentStateRatherThanTrustingOldEvent() {
        // This fake models the future adapter contract; it does not prove DB atomicity.
        Map<String, Boolean> pending = new HashMap<>();
        pending.put("new", true);
        pending.put("stale", false);
        var lookups = new AtomicInteger();
        var claims = new AtomicInteger();
        var handler = new AnalysisIntakeSignalHandler(ref -> {
            lookups.incrementAndGet();
            if (Boolean.TRUE.equals(pending.get(ref))) {
                pending.put(ref, false);
                claims.incrementAndGet();
            }
        });
        var replayed = new AnalysisIntakeChange("new", "c", "PENDING");
        handler.handle(replayed);
        handler.handle(replayed);
        handler.handle(new AnalysisIntakeChange("stale", "r", "PENDING"));
        assertThat(lookups).hasValue(3);
        assertThat(claims).hasValue(1);
    }

    @Test void adapterFailureIsPropagatedRatherThanReportedAsHandled() {
        var failure = new IllegalStateException("fixture failure");
        var handler = new AnalysisIntakeSignalHandler(ref -> { throw failure; });
        assertThatThrownBy(() -> handler.handle(new AnalysisIntakeChange("id", "c", "PENDING")))
                .isSameAs(failure);
    }

    @Test void pendingMustHaveANonblankReferenceAndUnknownInputFailsClosed() {
        var calls = new AtomicInteger();
        var handler = new AnalysisIntakeSignalHandler(ref -> calls.incrementAndGet());
        for (String reference : java.util.Arrays.asList(null, "", "  ")) {
            assertThatThrownBy(() -> handler.handle(new AnalysisIntakeChange(reference, "c", "PENDING")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> handler.handle(new AnalysisIntakeChange("id", "invalid", "PENDING")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle(new AnalysisIntakeChange("id", "u", "unknown")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.handle(null)).isInstanceOf(NullPointerException.class);
        assertThat(calls).hasValue(0);
    }

    @Test void everySupportedRowChangeRequiresReferenceEvenForIgnoredStates() {
        var calls = new AtomicInteger();
        var handler = new AnalysisIntakeSignalHandler(ref -> calls.incrementAndGet());
        for (String operation : List.of("c", "r", "u")) {
            for (String status : List.of("NOT_EVALUATED", "RUNNING", "COMPLETED", "FAILED")) {
                for (String reference : java.util.Arrays.asList(null, "", "  ")) {
                    assertThatThrownBy(() -> handler.handle(new AnalysisIntakeChange(reference, operation, status)))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
        assertThat(handler.handleTombstone()).isEqualTo(AnalysisIntakeSignalHandler.Result.IGNORED);
        assertThat(calls).hasValue(0);
    }

    @Test void nullAdapterIsRejected() {
        assertThatThrownBy(() -> new AnalysisIntakeSignalHandler(null)).isInstanceOf(NullPointerException.class);
    }
}
