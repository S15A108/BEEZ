package com.beez.backend.document;

import static org.assertj.core.api.Assertions.assertThat;
import com.beez.backend.document.validation.DocumentReviewUsageValidator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentReviewUsageValidatorTest {
    Map<String, Object> usage() {
        return new HashMap<>(Map.of("request_key", "review-1-call-1", "feature", "BEE_DRAFT", "model", "configured",
                "latency_ms", 10, "failed", false, "tokens_known", false));
    }

    @Test void unknownTokensStayUnknownAndUndeclaredFieldsAreNotPersisted() {
        var entry = usage(); entry.put("input_tokens", 999); entry.put("untrusted_payload", Map.of("anything", true));
        var result = DocumentReviewUsageValidator.extract(List.of(entry), "review-1");
        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).doesNotContainKeys("input_tokens", "output_tokens", "untrusted_payload");
    }

    @Test void foreignCallKeysAndInvalidKnownCountsCannotBeAttributedToProject() {
        var foreign = usage(); foreign.put("request_key", "other-project");
        var invalid = usage(); invalid.put("tokens_known", true); invalid.put("input_tokens", -1); invalid.put("output_tokens", 1);
        assertThat(DocumentReviewUsageValidator.extract(List.of(foreign, invalid), "review-1")).isEmpty();
    }

    @Test void failedActualCallsAreRetainedForAccounting() {
        var entry = usage(); entry.put("failed", true);
        assertThat(DocumentReviewUsageValidator.extract(List.of(entry), "review-1").getFirst()).containsEntry("failed", true);
    }
}
