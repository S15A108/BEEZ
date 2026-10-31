package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ValidationRetryPolicyTest {
    @Test
    void requiresCurrentFailuresAndRejectsAnyHumanReview() {
        assertThat(ValidationRequestService.retryable(List.of())).isFalse();
        assertThat(ValidationRequestService.retryable(List.of("RETRY"))).isTrue();
        assertThat(ValidationRequestService.retryable(List.of("RETRY", "HUMAN_REVIEW"))).isFalse();
        assertThat(ValidationRequestService.retryable(List.of("HUMAN_REVIEW"))).isFalse();
    }
}
