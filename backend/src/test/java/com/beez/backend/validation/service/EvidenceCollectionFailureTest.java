package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceCollectionFailureTest {
    @Test void exposesOnlyPublicCausesAndNeverInternalExceptionText() {
        var detail = EvidenceCollectionFailure.detail(List.of("source_evidence_unavailable", "db://secret", "source_evidence_unavailable"));
        assertThat(detail.get("reason_codes")).isEqualTo(List.of("source_evidence_unavailable"));
        assertThat(detail.get("type")).isEqualTo("EVIDENCE_COLLECTION_FAILED");
        assertThat(detail.get("retryable")).isEqualTo(true);
        assertThat(detail.toString()).doesNotContain("db://secret");
    }
    @Test void semanticInsufficiencyIsNotMisclassifiedAsCollectionFailure() {
        assertThat(EvidenceCollectionFailure.legacy("ai_evidence_insufficient", List.of("미해결 질문에 추가 확인이 필요합니다"))).isFalse();
        assertThat(EvidenceCollectionFailure.legacy("ai_evidence_insufficient", List.of("검색 또는 원문 근거 범위가 불완전합니다"))).isTrue();
    }
}
