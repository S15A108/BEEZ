package com.beez.backend.document.service;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewRerunPolicyTest {
    @Test void onlyTheNewestRejectedSubmissionSuppliesTheRerunContext() {
        var rejected = Map.<String, Object>of("id", 42L, "status", "REJECTED", "rejection_reason", "누락 항목");
        assertThat(ReviewRerunPolicy.context(List.of(rejected))).containsExactlyInAnyOrderEntriesOf(
                Map.of("mode", "RERUN", "rejection_submission_id", "42", "rejection_reason", "누락 항목"));
        assertThat(ReviewRerunPolicy.context(List.of())).isEmpty();
        assertThat(ReviewRerunPolicy.context(List.of(Map.of("id", 43L, "status", "WITHDRAWN"), rejected))).isEmpty();
        assertThat(ReviewRerunPolicy.context(List.of(Map.of("id", 44L, "status", "APPROVED"), rejected))).isEmpty();
    }

    @Test void missingRejectionReasonNeverSilentlyBecomesAnOrdinaryReview() {
        assertThatThrownBy(() -> ReviewRerunPolicy.context(List.of(Map.of("id", 42L, "status", "REJECTED"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ReviewRerunPolicy.context(List.of(Map.of("id", 42L, "status", "REJECTED", "rejection_reason", " "))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void rerunFingerprintIncludesRejectionIdentityAndPreservesLegacyKeys() {
        var first = ReviewRerunPolicy.context(List.of(Map.of("id", 42L, "status", "REJECTED", "rejection_reason", "사유 1")));
        var second = ReviewRerunPolicy.context(List.of(Map.of("id", 43L, "status", "REJECTED", "rejection_reason", "사유 1")));
        var changedReason = ReviewRerunPolicy.context(List.of(Map.of("id", 42L, "status", "REJECTED", "rejection_reason", "사유 2")));
        String legacy = DocumentReviewService.reviewFingerprint(2, "ref-hash", "content-hash", Map.of());
        assertThat(legacy).isEqualTo(com.beez.backend.designitem.support.DesignJson.hash(
                List.of("document-review-v2", 2L, "ref-hash", "content-hash")));
        String original = DocumentReviewService.reviewFingerprint(2, "ref-hash", "content-hash", first);
        assertThat(DocumentReviewService.reviewFingerprint(2, "ref-hash", "content-hash", ReviewRerunPolicy.fromInput(first)))
                .isEqualTo(original);
        assertThat(DocumentReviewService.reviewFingerprint(2, "ref-hash", "content-hash", second)).isNotEqualTo(original);
        assertThat(DocumentReviewService.reviewFingerprint(2, "ref-hash", "content-hash", changedReason)).isNotEqualTo(original);
    }
}
