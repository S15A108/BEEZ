package com.beez.backend.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentValuesTest {
    @Test void conflictKeepsMachineCodeAndReturnsKoreanGuidance() {
        for (String code : List.of("idempotency_conflict", "version_conflict", "analysis_running",
                "analysis_not_failed", "baseline_not_published", "index_not_ready", "baseline_changed", "unknown_conflict")) {
            var error = DocumentValues.conflict(code);
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getStatus().value()).isEqualTo(409);
            assertThat(error.getMessage()).containsPattern("[가-힣]").isNotEqualTo(code);
        }
    }
}
