package com.beez.backend.document.service;

import java.util.List;
import java.util.Map;

/** A newer submission supersedes any earlier rejection, including after withdrawal. */
final class ReviewRerunPolicy {
    private ReviewRerunPolicy() { }

    static Map<String, Object> context(List<Map<String, Object>> newestFirst) {
        if (newestFirst.isEmpty() || !"REJECTED".equals(newestFirst.getFirst().get("status"))) return Map.of();
        var latest = newestFirst.getFirst();
        Object id = latest.get("id"), reason = latest.get("rejection_reason");
        if (id == null || !(reason instanceof String text) || text.isBlank()) {
            throw new IllegalStateException("Rejected submission is missing its identity or reason");
        }
        return Map.of("mode", "RERUN", "rejection_submission_id", id.toString(), "rejection_reason", text);
    }

    static Map<String, Object> fromInput(Map<String, Object> input) {
        if (!"RERUN".equals(input.get("mode"))) return Map.of();
        return Map.of("mode", "RERUN", "rejection_submission_id", input.get("rejection_submission_id"),
                "rejection_reason", input.get("rejection_reason"));
    }
}
