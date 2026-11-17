package com.beez.backend.validation.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public allowlisted diagnostics; no exception text, source identifiers or infrastructure details.
 * The only identifiers are the project's own design items whose source could not be used (design_items), so the
 * leader can fix that item's source location (S15P31A108-422).
 */
public final class EvidenceCollectionFailure {
    public static final String CODE = "evidence_collection_failed";
    public static final String KEY = "execution_failure";
    private static final String UNKNOWN = "unknown_collection_failure";
    private static final Set<String> TRANSIENT = Set.of("evidence_fetch_failed");
    private static final Set<String> REASONS = Set.of("search_query_too_long", "search_query_limit",
            "search_results_truncated", "design_candidate_limit", "related_task_limit", "source_evidence_unavailable",
            "evidence_fetch_failed", "design_reference_limit", "unresolved_limit", "source_confirmation_required", UNKNOWN);
    private EvidenceCollectionFailure() {}

    /** Design items listed with a source failure, at most this many. */
    static final int MAX_DESIGN_ITEMS = 20;
    private static final Set<String> SOURCE_REASONS = Set.of("source_evidence_unavailable", "source_confirmation_required");

    public static Map<String, Object> detail(List<String> reasons) {
        return detail(reasons, List.of());
    }

    /**
     * @param designItems approved items whose source evidence was unavailable: design_item_id, design_item_version_id, key.
     *                    Kept only beside a source reason.
     */
    public static Map<String, Object> detail(List<String> reasons, List<Map<String, Object>> designItems) {
        return detail(reasons, true, designItems);
    }

    private static Map<String, Object> detail(List<String> reasons, boolean retryAllowed) {
        return detail(reasons, retryAllowed, List.of());
    }

    private static Map<String, Object> detail(List<String> reasons, boolean retryAllowed, List<?> designItems) {
        // Keep an explicit unknown marker: dropping an unknown cause beside a transient one
        // would incorrectly make the persisted/read-back classification retryable.
        var safe = reasons.stream().map(reason -> reason != null && REASONS.contains(reason) ? reason : UNKNOWN).distinct().toList();
        if (safe.isEmpty()) safe = List.of(UNKNOWN);
        boolean retryable = retryAllowed && safe.stream().allMatch(TRANSIENT::contains);
        String message = retryable
                ? "검증에 필요한 검색 결과나 문서 근거를 모두 모으지 못했어요. 계획은 바꾸지 않았어요. 검색·문서 준비 상태를 확인하고 다시 시도해 주세요."
                : safe.contains("source_confirmation_required") && !safe.contains("source_evidence_unavailable")
                ? "문서가 바뀌어 설계 항목이 인용한 원문 위치를 확인하지 못했어요. 계획은 바꾸지 않았어요. 해당 설계 항목의 출처 위치를 확인·정정한 뒤 새 검증을 요청해 주세요."
                : safe.contains("source_evidence_unavailable")
                ? "승인 문서에서 필요한 근거 범위를 확인하지 못했어요. 계획은 바꾸지 않았어요. 문서의 승인 상태를 확인하고, 해당 설계 항목의 출처 위치를 지정하거나 인용 없음으로 확정한 뒤 새 검증을 요청해 주세요."
                : "검증에 필요한 검색 결과나 문서 근거를 모두 모으지 못했어요. 계획은 바꾸지 않았어요. 원인을 확인하고 계획·검색 범위와 문서 준비 상태를 보완한 뒤 새 검증을 요청해 주세요.";
        var items = safe.stream().anyMatch(SOURCE_REASONS::contains) ? designItems(designItems) : List.<Map<String, String>>of();
        if (items.isEmpty()) {
            return Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", safe, "retryable", retryable, "message", message);
        }
        return Map.of("type", "EVIDENCE_COLLECTION_FAILED", "reason_codes", safe, "retryable", retryable, "message", message,
                "design_items", items);
    }

    /** Only well-formed entries survive, so a stored or caller value cannot inject other fields. */
    private static List<Map<String, String>> designItems(List<?> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value instanceof Map<?, ?> map && digits(map.get("design_item_id"))
                        && digits(map.get("design_item_version_id")) && map.get("key") instanceof String key
                        && !key.isBlank() && key.length() <= 100)
                .map(value -> (Map<?, ?>) value)
                .map(map -> Map.of("design_item_id", map.get("design_item_id").toString(),
                        "design_item_version_id", map.get("design_item_version_id").toString(),
                        "key", map.get("key").toString()))
                .distinct().limit(MAX_DESIGN_ITEMS).toList();
    }

    private static boolean digits(Object value) {
        return value instanceof String text && !text.isEmpty() && text.length() <= 19 && text.chars().allMatch(Character::isDigit);
    }

    /** Reclassify historical records instead of trusting an old RETRY finding or retryable=true. */
    public static Map<String, Object> fromStored(String code, Object stored, List<String> errorMessages) {
        if (!CODE.equals(code) && !legacy(code, errorMessages)) return null;
        if (stored instanceof Map<?, ?> detail && "EVIDENCE_COLLECTION_FAILED".equals(detail.get("type"))) {
            List<String> reasons = detail.get("reason_codes") instanceof List<?> values
                    ? values.stream().map(value -> value instanceof String text ? text : UNKNOWN).toList() : List.of();
            return detail(reasons, Boolean.TRUE.equals(detail.get("retryable")),
                    detail.get("design_items") instanceof List<?> items ? items : List.of());
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
