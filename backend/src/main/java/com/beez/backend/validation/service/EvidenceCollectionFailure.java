package com.beez.backend.validation.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public allowlisted diagnostics; no exception text, source identifiers or infrastructure details. */
public final class EvidenceCollectionFailure {
    public static final String CODE = "evidence_collection_failed";
    public static final String KEY = "execution_failure";
    private static final String UNKNOWN = "unknown_collection_failure";
    private static final Set<String> TRANSIENT = Set.of("evidence_fetch_failed");
    private static final Set<String> REASONS = Set.of("search_query_too_long", "search_query_limit",
            "search_results_truncated", "design_candidate_limit", "related_task_limit", "source_evidence_unavailable",
            "evidence_fetch_failed", "design_reference_limit", "unresolved_limit", UNKNOWN);
    private EvidenceCollectionFailure() {}

    public static Map<String, Object> detail(List<String> reasons) {
        return detail(reasons, true);
    }

    private static Map<String, Object> detail(List<String> reasons, boolean retryAllowed) {
        // Keep an explicit unknown marker: dropping an unknown cause beside a transient one
        // would incorrectly make the persisted/read-back classification retryable.
        var safe = reasons.stream().map(reason -> reason != null && REASONS.contains(reason) ? reason : UNKNOWN).distinct().toList();
        if (safe.isEmpty()) safe = List.of(UNKNOWN);
        boolean retryable = retryAllowed && safe.stream().allMatch(TRANSIENT::contains);
        String message = retryable
                ? "검증에 필요한 검색 결과나 문서 근거를 모두 모으지 못했어요. 계획은 바꾸지 않았어요. 검색·문서 준비 상태를 확인하고 다시 시도해 주세요."
                : safe.contains("source_evidence_unavailable")
                ? "승인 문서에서 필요한 근거 범위를 확인하지 못했어요. 문서의 승인 상태와 출처 위치·인용 범위를 보완한 뒤 새 검증을 요청해 주세요."
                : "검증에 필요한 검색 결과나 문서 근거를 모두 모으지 못했어요. 계획은 바꾸지 않았어요. 원인을 확인하고 계획·검색 범위와 문서 준비 상태를 보완한 뒤 새 검증을 요청해 주세요.";
        return Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", safe, "retryable", retryable, "message", message);
    }

    /** Reclassify historical records instead of trusting an old RETRY finding or retryable=true. */
    public static Map<String, Object> fromStored(String code, Object stored, List<String> errorMessages) {
        if (!CODE.equals(code) && !legacy(code, errorMessages)) return null;
        if (stored instanceof Map<?, ?> detail && "EVIDENCE_COLLECTION_FAILED".equals(detail.get("type"))) {
            List<String> reasons = detail.get("reason_codes") instanceof List<?> values
                    ? values.stream().map(value -> value instanceof String text ? text : UNKNOWN).toList() : List.of();
            return detail(reasons, Boolean.TRUE.equals(detail.get("retryable")));
        }
        // Only the old fetch-error code establishes a transient cause without typed diagnostics.
        // The old incomplete-range messages also covered deterministic limits, so remain unknown.
        return detail(stored == null && "evidence_fetch_failed".equals(code)
                ? List.of("evidence_fetch_failed") : List.of());
    }

    public static boolean legacy(String code, List<String> messages) {
        return "evidence_fetch_failed".equals(code)
                || "ai_evidence_insufficient".equals(code) && !messages.isEmpty()
                && messages.stream().allMatch(message -> Set.of("검색 또는 원문 근거 범위가 불완전합니다",
                        "분석 후보 검색 또는 원문 근거 범위가 불완전합니다").contains(message));
    }
}
