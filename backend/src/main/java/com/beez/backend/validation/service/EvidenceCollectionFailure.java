package com.beez.backend.validation.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public allowlisted diagnostics; no exception text, source identifiers or infrastructure details. */
public final class EvidenceCollectionFailure {
    public static final String CODE = "evidence_collection_failed";
    public static final String KEY = "execution_failure";
    private static final Set<String> REASONS = Set.of("search_query_too_long", "search_query_limit",
            "search_results_truncated", "design_candidate_limit", "related_task_limit", "source_evidence_unavailable",
            "evidence_fetch_failed");
    private EvidenceCollectionFailure() {}

    public static Map<String, Object> detail(List<String> reasons) {
        var safe = reasons.stream().filter(REASONS::contains).distinct().toList();
        if (safe.isEmpty()) safe = List.of("evidence_fetch_failed");
        return Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", safe, "retryable", true,
                "message", "검증에 필요한 검색 결과나 문서 근거를 모두 모으지 못했어요. 계획은 바꾸지 않았어요. 검색·문서 준비 상태를 확인하고 다시 시도해 주세요.");
    }

    public static boolean legacy(String code, List<String> messages) {
        return "evidence_fetch_failed".equals(code)
                || "ai_evidence_insufficient".equals(code) && !messages.isEmpty()
                && messages.stream().allMatch(message -> Set.of("검색 또는 원문 근거 범위가 불완전합니다",
                        "분석 후보 검색 또는 원문 근거 범위가 불완전합니다").contains(message));
    }
}
