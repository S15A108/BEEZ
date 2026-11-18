package com.beez.backend.cdc.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProcessingRetryPolicyTest {
    private static final Instant START = Instant.parse("2026-10-30T01:00:00Z");

    @Test void eachRetryIsScheduledAfterTheCorrespondingFailure() {
        assertThat(ProcessingRetryPolicy.afterFailure(1, START))
                .isEqualTo(new ProcessingRetryPolicy.Retry(2, START.plusSeconds(1)));
        assertThat(ProcessingRetryPolicy.afterFailure(2, START))
                .isEqualTo(new ProcessingRetryPolicy.Retry(3, START.plusSeconds(5)));
        assertThat(ProcessingRetryPolicy.afterFailure(3, START))
                .isEqualTo(new ProcessingRetryPolicy.Retry(4, START.plusSeconds(30)));
        assertThat(ProcessingRetryPolicy.afterFailure(4, START))
                .isInstanceOf(ProcessingRetryPolicy.Exhausted.class);
    }

    @Test void consecutiveInstantFailuresWaitOneFiveAndThirtySeconds() {
        Instant failedAt = START;
        long[] expectedElapsed = {1, 6, 36};
        for (int attempt = 1; attempt <= 3; attempt++) {
            var retry = (ProcessingRetryPolicy.Retry) ProcessingRetryPolicy.afterFailure(attempt, failedAt);
            assertThat(retry.nextAttempt()).isEqualTo(attempt + 1);
            assertThat(retry.notBefore()).isEqualTo(START.plusSeconds(expectedElapsed[attempt - 1]));
            failedAt = retry.notBefore();
        }
        assertThat(ProcessingRetryPolicy.afterFailure(4, failedAt))
                .isInstanceOf(ProcessingRetryPolicy.Exhausted.class);
    }

    @Test void rejectsOutOfRangeAttemptsIncludingIntegerExtremes() {
        for (int attempt : new int[] {Integer.MIN_VALUE, -1, 0, 5, Integer.MAX_VALUE}) {
            assertThatThrownBy(() -> ProcessingRetryPolicy.afterFailure(attempt, START))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsMissingTimeForEveryOutcome() {
        assertThatThrownBy(() -> ProcessingRetryPolicy.afterFailure(1, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ProcessingRetryPolicy.afterFailure(4, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test void rejectsTimestampOverflowWithoutWrappingOrRetryingEarly() {
        assertThatThrownBy(() -> ProcessingRetryPolicy.afterFailure(3, Instant.MAX.minusSeconds(29)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Retry time");
        assertThat(ProcessingRetryPolicy.afterFailure(3, Instant.MAX.minusSeconds(30)))
                .isEqualTo(new ProcessingRetryPolicy.Retry(4, Instant.MAX));
        assertThat(ProcessingRetryPolicy.afterFailure(4, Instant.MAX))
                .isInstanceOf(ProcessingRetryPolicy.Exhausted.class);
    }
}
