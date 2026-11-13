package com.beez.backend.cdc.observability;

import java.util.List;
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
        double oldestPendingSeconds,
        Map<String, Double> unresolved) {

    /**
     * 남은 FAILED·확인 필요 미평가 행을 손쓸 수 있는지로 나눈다 (S15P31A108-412).
     * ACTIONABLE = 그 접수의 baseline 이 프로젝트의 현재 baseline 이고 프로젝트가 보관되지 않음.
     * 그 밖의 행은 다시 분석할 수 없다(발행 후 분석은 현재 baseline 에서만 돈다, -408). 알림은 ACTIONABLE 만 센다.
     */
    public static final List<String> UNRESOLVED_KINDS = List.of("FAILED", "NOT_EVALUATED_REVIEW");
    public static final List<String> UNRESOLVED_SCOPES = List.of("ACTIONABLE", "NOT_ACTIONABLE");

    public static String unresolvedKey(String kind, String scope) {
        return kind + "/" + scope;
    }
}
