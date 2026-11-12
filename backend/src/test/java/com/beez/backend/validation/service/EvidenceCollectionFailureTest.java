package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EvidenceCollectionFailureTest {
    @Test void exposesOnlyPublicCausesAndNeverInternalExceptionText() {
        var detail = EvidenceCollectionFailure.detail(List.of("source_evidence_unavailable", "db://secret", "source_evidence_unavailable"));
        assertThat(detail.get("reason_codes")).isEqualTo(List.of("source_evidence_unavailable", "unknown_collection_failure"));
        assertThat(detail.get("type")).isEqualTo("EVIDENCE_COLLECTION_FAILED");
        assertThat(detail.get("retryable")).isEqualTo(false);
        assertThat(detail.toString()).doesNotContain("db://secret");
    }

    @Test void onlyExplicitFetchFailuresAreTransient() {
        var detail = EvidenceCollectionFailure.detail(List.of("evidence_fetch_failed", "evidence_fetch_failed"));
        assertThat(detail.get("reason_codes")).isEqualTo(List.of("evidence_fetch_failed"));
        assertThat(detail.get("retryable")).isEqualTo(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"search_query_too_long", "search_query_limit", "search_results_truncated",
            "design_candidate_limit", "related_task_limit", "source_evidence_unavailable",
            "design_reference_limit", "unresolved_limit", "unknown_collection_failure"})
    void persistentCausesAreHumanReviewEvenWhenPreviouslyStoredAsRetry(String cause) {
        var detail = EvidenceCollectionFailure.detail(List.of(cause));
        assertThat(detail.get("retryable")).isEqualTo(false);
        assertThat(detail.get("reason_codes")).isEqualTo(List.of(cause));
        var old = Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", List.of(cause), "retryable", true);
        assertThat(EvidenceCollectionFailure.fromStored(EvidenceCollectionFailure.CODE, old, List.of()).get("retryable")).isEqualTo(false);
    }

    @Test void unknownAndMixedCausesStayNonRetryableAfterSanitizationAndReadback() {
        assertThat(EvidenceCollectionFailure.detail(List.of()).get("retryable")).isEqualTo(false);
        assertThat(EvidenceCollectionFailure.detail(List.of("evidence_fetch_failed", "search_query_limit")).get("retryable")).isEqualTo(false);
        var unknown = EvidenceCollectionFailure.detail(List.of("evidence_fetch_failed", "internal://unrecognized"));
        assertThat(unknown.get("retryable")).isEqualTo(false);
        assertThat(unknown.get("reason_codes")).isEqualTo(List.of("evidence_fetch_failed", "unknown_collection_failure"));
        assertThat(EvidenceCollectionFailure.fromStored(EvidenceCollectionFailure.CODE, unknown, List.of()).get("retryable")).isEqualTo(false);
        assertThat(unknown.toString()).doesNotContain("internal://unrecognized");
    }

    @Test void storedExplicitHumanReviewCannotBeOverriddenByTransientCodes() {
        var stored = Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", List.of("evidence_fetch_failed"), "retryable", false);
        assertThat(EvidenceCollectionFailure.fromStored(EvidenceCollectionFailure.CODE, stored, List.of()).get("retryable")).isEqualTo(false);
        var malformed = Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", List.of("evidence_fetch_failed", 7), "retryable", true);
        assertThat(EvidenceCollectionFailure.fromStored(EvidenceCollectionFailure.CODE, malformed, List.of()).get("retryable")).isEqualTo(false);
        var malformedFlag = Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", List.of("evidence_fetch_failed"), "retryable", "true");
        assertThat(EvidenceCollectionFailure.fromStored(EvidenceCollectionFailure.CODE, malformedFlag, List.of()).get("retryable")).isEqualTo(false);
    }

    @Test void legacyFetchKeepsRetryButUnknownIncompleteRangesRequireReview() {
        assertThat(EvidenceCollectionFailure.fromStored("evidence_fetch_failed", null, List.of()).get("retryable")).isEqualTo(true);
        for (String message : List.of("검색 또는 원문 근거 범위가 불완전합니다", "분석 후보 검색 또는 원문 근거 범위가 불완전합니다")) {
            var unknown = EvidenceCollectionFailure.fromStored("ai_evidence_insufficient", null, List.of(message));
            assertThat(unknown.get("retryable")).isEqualTo(false);
            assertThat(unknown.get("reason_codes")).isEqualTo(List.of("unknown_collection_failure"));
        }
        assertThat(EvidenceCollectionFailure.fromStored("ai_timeout", null, List.of())).isNull();
    }
    @Test void semanticInsufficiencyIsNotMisclassifiedAsCollectionFailure() {
        assertThat(EvidenceCollectionFailure.legacy("ai_evidence_insufficient", List.of("미해결 질문에 추가 확인이 필요합니다"))).isFalse();
        assertThat(EvidenceCollectionFailure.legacy("ai_evidence_insufficient", List.of("검색 또는 원문 근거 범위가 불완전합니다"))).isTrue();
    }
}
