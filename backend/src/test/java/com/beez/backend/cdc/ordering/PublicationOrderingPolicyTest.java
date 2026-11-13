package com.beez.backend.cdc.ordering;

import static com.beez.backend.cdc.ordering.PublicationOrderingPolicy.Outcome.*;
import static com.beez.backend.cdc.ordering.PublicationOrderingPolicy.SkipEvidence.*;
import static com.beez.backend.cdc.ordering.PublicationOrderingPolicy.State.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class PublicationOrderingPolicyTest {
    private static final long PROJECT = 10;

    private static PublicationOrderingPolicy.PublishedVersion row(int version, PublicationOrderingPolicy.State state,
                                                                   PublicationOrderingPolicy.SkipEvidence evidence) {
        return new PublicationOrderingPolicy.PublishedVersion(PROJECT, version, state, evidence);
    }

    @Test void initialBaselinePendingRequiresReconciliationInsteadOfAutomaticClaim() {
        var decision = PublicationOrderingPolicy.evaluate(PROJECT, 1, true, List.of(row(1, PENDING, NONE)));
        assertThat(decision.outcome()).isEqualTo(REQUIRES_RECONCILIATION);
        assertThat(decision.blockingVersion()).isEqualTo(1);
    }

    @Test void confirmedPre197BackfillDoesNotBlockLaterPendingVersion() {
        var decision = PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                List.of(row(3, PENDING, NONE), row(1, NOT_EVALUATED, INITIAL_BASELINE),
                        row(2, NOT_EVALUATED, PRE_197_BACKFILL)));
        assertThat(decision.outcome()).isEqualTo(READY_FOR_ATOMIC_CLAIM);
    }

    @Test void unsupportedDocumentChangeAllowsLaterWorkButIsNotItselfPending() {
        var initial = row(1, NOT_EVALUATED, INITIAL_BASELINE);
        var skipped = row(2, NOT_EVALUATED, DOCUMENT_CHANGE_UNAVAILABLE);
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                List.of(initial, skipped, row(3, PENDING, NONE))).outcome()).isEqualTo(READY_FOR_ATOMIC_CLAIM);
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 2, true,
                List.of(initial, skipped)).outcome()).isEqualTo(NOT_PENDING);
        assertThatThrownBy(() -> row(2, FAILED, DOCUMENT_CHANGE_UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 뒤 버전이 발행된 뒤에는 앞 버전을 다시 분석할 수 없으므로(현재 baseline 이 아님), 끝난 앞 버전을 기다리면 영구히 멈춘다.
     * 아직 처리 중인 앞 버전만 기다린다 (S15P31A108-408).
     */
    @Test void earlierVersionWaitsOnlyWhilePendingOrRunning() {
        for (var state : List.of(PENDING, RUNNING)) {
            var decision = PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                    List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), row(2, state, NONE), row(3, PENDING, NONE)));
            assertThat(decision.outcome()).isEqualTo(WAITING_FOR_PREDECESSOR);
            assertThat(decision.blockingVersion()).isEqualTo(2);
        }
        for (var finished : List.of(row(2, FAILED, NONE), row(2, NOT_EVALUATED, NONE))) {
            assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                    List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), finished, row(3, PENDING, NONE))).outcome())
                    .isEqualTo(READY_FOR_ATOMIC_CLAIM);
        }
    }

    @Test void completedEarlierWorkAllowsNextVersionButUnknownStateDoesNot() {
        var v1 = row(1, NOT_EVALUATED, INITIAL_BASELINE);
        var v3 = row(3, PENDING, NONE);
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                List.of(v1, row(2, COMPLETED, NONE), v3)).outcome()).isEqualTo(READY_FOR_ATOMIC_CLAIM);
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                List.of(v1, row(2, UNKNOWN, NONE), v3)).outcome()).isEqualTo(REQUIRES_RECONCILIATION);
    }

    @Test void staleUnverifiedAndIncompleteSnapshotsNeverAuthorizeWork() {
        var rows = List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), row(2, PENDING, NONE));
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 2, false, rows).outcome()).isEqualTo(REQUIRES_RECONCILIATION);
        var missing = PublicationOrderingPolicy.evaluate(PROJECT, 3, true,
                List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), row(3, PENDING, NONE)));
        assertThat(missing.outcome()).isEqualTo(REQUIRES_RECONCILIATION);
        assertThat(missing.blockingVersion()).isEqualTo(2);
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 2, true, List.of(rows.getFirst())).outcome())
                .isEqualTo(REQUIRES_RECONCILIATION);
    }

    @Test void repeatedDeliveryForNonPendingTargetDoesNotClaimAgain() {
        for (var state : List.of(RUNNING, COMPLETED, FAILED)) {
            var decision = PublicationOrderingPolicy.evaluate(PROJECT, 2, true,
                    List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), row(2, state, NONE)));
            assertThat(decision.outcome()).isEqualTo(NOT_PENDING);
        }
        assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 2, true,
                List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), row(2, NOT_EVALUATED, PRE_197_BACKFILL))).outcome())
                .isEqualTo(NOT_PENDING);
        for (var state : List.of(UNKNOWN, NOT_EVALUATED)) {
            assertThat(PublicationOrderingPolicy.evaluate(PROJECT, 2, true,
                    List.of(row(1, NOT_EVALUATED, INITIAL_BASELINE), row(2, state, NONE))).outcome())
                    .isEqualTo(REQUIRES_RECONCILIATION);
        }
    }

    @Test void duplicateAndCrossProjectVersionsFailClosed() {
        var duplicate = PublicationOrderingPolicy.evaluate(PROJECT, 2, true,
                List.of(row(1, COMPLETED, NONE), row(1, COMPLETED, NONE), row(2, PENDING, NONE)));
        assertThat(duplicate.outcome()).isEqualTo(REQUIRES_RECONCILIATION);
        assertThat(duplicate.blockingVersion()).isEqualTo(1);
        var otherProject = new PublicationOrderingPolicy.PublishedVersion(11, 1, COMPLETED, NONE);
        assertThatThrownBy(() -> PublicationOrderingPolicy.evaluate(PROJECT, 2, true,
                List.of(otherProject, row(2, PENDING, NONE)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void initialSkipCannotBeClaimedForLaterVersion() {
        assertThatThrownBy(() -> row(2, NOT_EVALUATED, INITIAL_BASELINE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> row(1, PENDING, PRE_197_BACKFILL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void hugeTargetWithMissingVersionsDoesNotIterateOverItsNumericRange() {
        var decision = PublicationOrderingPolicy.evaluate(PROJECT, Integer.MAX_VALUE, true,
                List.of(row(Integer.MAX_VALUE, PENDING, NONE)));
        assertThat(decision.outcome()).isEqualTo(REQUIRES_RECONCILIATION);
        assertThat(decision.blockingVersion()).isEqualTo(1);
    }
}
