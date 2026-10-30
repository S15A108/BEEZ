package com.beez.backend.cdc.retry;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;

/**
 * Pure scheduling policy for retryable analysis-processing failures.
 *
 * <p>Attempt numbers are one-based: one initial attempt and at most three
 * retries. Delays are measured from each failed attempt, not the initial
 * request. This policy neither schedules work nor persists state; the eventual
 * #197 adapter must atomically persist attempt/next-attempt time and enforce
 * claim and lease fencing. Kafka transport retries have a separate policy.
 */
public final class ProcessingRetryPolicy {
    public static final int MAX_ATTEMPTS = 4;

    private ProcessingRetryPolicy() {}

    public sealed interface Decision permits Retry, Exhausted {}

    /** Earliest time at which the next attempt may be claimed. */
    public record Retry(int nextAttempt, Instant notBefore) implements Decision {}

    /** The automatic retry budget is exhausted; do not silently pass work. */
    public record Exhausted() implements Decision {}

    /**
     * Returns the next automatic action following a retryable failed attempt.
     * Callers must not apply this to stale lease holders or non-retryable
     * failures. A manually initiated retry cycle requires a separate contract.
     *
     * @param completedAttempt failed attempt number from 1 through 4
     * @param failedAt time at which that attempt failed
     * @throws IllegalArgumentException for an invalid attempt or timestamp overflow
     * @throws NullPointerException if failedAt is null
     */
    public static Decision afterFailure(int completedAttempt, Instant failedAt) {
        if (completedAttempt < 1 || completedAttempt > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Failed attempt must be between 1 and 4");
        }
        Objects.requireNonNull(failedAt, "failedAt");
        if (completedAttempt == MAX_ATTEMPTS) return new Exhausted();

        long delaySeconds = switch (completedAttempt) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 30;
            default -> throw new IllegalStateException("Unexpected failed attempt");
        };
        try {
            return new Retry(completedAttempt + 1, failedAt.plusSeconds(delaySeconds));
        } catch (DateTimeException | ArithmeticException ex) {
            throw new IllegalArgumentException("Retry time exceeds supported instant range", ex);
        }
    }
}
