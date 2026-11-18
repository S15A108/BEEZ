package com.beez.backend.cdc.observability;

import java.util.Map;

/** A single successful, database-backed observation; all numbers are global aggregates. */
public record PublishedAnalysisMetricsSnapshot(
        Map<String, Double> intakes,
        Map<String, Double> evaluations,
        Map<String, Double> attempts,
        double notEvaluatedExpected,
        double notEvaluatedRequiresReview,
        double blockedProjects,
        double pendingRetries,
        double expiredLeases,
        double startedRetries,
        double recordedRetryAttempts,
        double oldestPendingSeconds) {
}
