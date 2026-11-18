package com.beez.backend.cdc.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnalysisIntakeEventPolicyTest {
    @Test void pendingInsertSnapshotAndUpdateOnlySignalCurrentRowCheck() {
        for (String op : List.of("c", "r", "u")) {
            assertThat(AnalysisIntakeEventPolicy.decide(op, "PENDING"))
                    .isEqualTo(AnalysisIntakeEventPolicy.Decision.CHECK_CURRENT_INTAKE);
        }
    }

    @Test void initialAndBackfilledNotEvaluatedNeverRequestAutomaticAnalysis() {
        for (String op : List.of("c", "r", "u")) {
            assertThat(AnalysisIntakeEventPolicy.decide(op, "NOT_EVALUATED"))
                    .isEqualTo(AnalysisIntakeEventPolicy.Decision.IGNORE);
        }
    }

    @Test void processingAndTerminalStatusChangesNeverCreateAnalysisWork() {
        for (String status : List.of("RUNNING", "COMPLETED", "FAILED")) {
            for (String op : List.of("c", "r", "u")) {
                assertThat(AnalysisIntakeEventPolicy.decide(op, status))
                        .isEqualTo(AnalysisIntakeEventPolicy.Decision.IGNORE);
            }
        }
    }

    @Test void deletesFailClosedUntilRetentionPolicyIsAgreed() {
        assertThatThrownBy(() -> AnalysisIntakeEventPolicy.decide("d", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingAndUnknownOperationsFailClosed() {
        assertThatThrownBy(() -> AnalysisIntakeEventPolicy.decide(null, "PENDING"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisIntakeEventPolicy.decide("x", "PENDING"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void missingAndUnknownStatusesFailClosed() {
        assertThatThrownBy(() -> AnalysisIntakeEventPolicy.decide("c", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisIntakeEventPolicy.decide("u", "UNEXPECTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
