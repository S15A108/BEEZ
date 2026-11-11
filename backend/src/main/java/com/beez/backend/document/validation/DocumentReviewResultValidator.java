package com.beez.backend.document.validation;

import static com.beez.backend.designitem.support.DesignJson.canonical;

import com.beez.backend.common.api.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;

/** BE-side v2 contract. Missing references are the only accepted cause of a partial review. */
public final class DocumentReviewResultValidator {
    public static final List<String> STEPS = List.of("BODY", "API_SPEC", "ERD", "REQ_SPEC");

    public static Map<String, Object> validate(Map<String, Object> envelope, Map<String, Object> input) {
        if (envelope == null || !"MODEL".equals(envelope.get("origin"))
                || !(envelope.get("result") instanceof Map<?, ?> raw)
                || !(envelope.get("usage") instanceof List<?> usage) || usage.isEmpty()
                || usage.stream().noneMatch(item -> item instanceof Map<?, ?> u
                        && Boolean.FALSE.equals(u.get("failed"))
                        && u.get("model") instanceof String model && !model.isBlank())) throw invalidAi();
        var result = new LinkedHashMap<String, Object>();
        for (String field : List.of("document_id", "revision", "content_hash", "reference_hash", "request_key")) {
            if ("revision".equals(field)) {
                if (!(raw.get(field) instanceof Number n) || n.doubleValue() != n.longValue()
                        || n.longValue() != ((Number) input.get(field)).longValue()) throw invalidAi();
            } else if (!Objects.equals(input.get(field), raw.get(field))) throw invalidAi();
            result.put(field, raw.get(field));
        }
        result.put("summary", text(raw.get("summary"), 10000));
        var refs = references(input);
        var byStep = new HashMap<String, Map<String, Object>>();
        var used = new TreeSet<String>();
        var unavailable = new ArrayList<String>();
        if (!(raw.get("steps") instanceof List<?> steps) || steps.size() != STEPS.size()) throw invalidAi();
        for (Object entry : steps) {
            if (!(entry instanceof Map<?, ?> step)) throw invalidAi();
            String code = text(step.get("step"), 32), state = text(step.get("status"), 32);
            String reason = text(step.get("reason"), 64);
            if (!STEPS.contains(code) || byStep.containsKey(code)) throw invalidAi();
            var ids = ids(step.get("reference_ids"));
            var expected = new TreeSet<String>();
            refs.forEach((id, ref) -> { if (code.equals(ref.get("kind"))) expected.add(id); });
            if ("BODY".equals(code)) {
                if (!"COMPLETE".equals(state) || !"REVIEWED".equals(reason) || !ids.isEmpty()) throw invalidAi();
            } else if (code.equals(input.get("kind"))) {
                if (!"SKIPPED".equals(state) || !"SAME_KIND".equals(reason) || !ids.isEmpty()) throw invalidAi();
            } else if (expected.isEmpty()) {
                if (!Set.of("PARTIAL", "SKIPPED").contains(state)
                        || !"REFERENCE_UNAVAILABLE".equals(reason) || !ids.isEmpty()) throw invalidAi();
                unavailable.add(code);
            } else if (!"COMPLETE".equals(state) || !"REVIEWED".equals(reason) || !expected.equals(new TreeSet<>(ids))) {
                throw invalidAi();
            }
            used.addAll(ids);
            byStep.put(code, Map.of("step", code, "status", state, "reason", reason, "reference_ids", ids));
        }
        String status = unavailable.isEmpty() ? "COMPLETE" : "PARTIAL";
        if (!status.equals(raw.get("review_status")) || !used.equals(new TreeSet<>(ids(raw.get("references_used"))))) {
            throw invalidAi();
        }
        if (!(raw.get("suggestions") instanceof List<?> entries) || entries.size() > 30) throw invalidAi();
        var suggestions = new ArrayList<Map<String, Object>>();
        var ranges = new ArrayList<int[]>();
        String content = input.get("content").toString();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> suggestion)) throw invalidAi();
            String type = text(suggestion.get("type"), 32), step = text(suggestion.get("step"), 32);
            if (!Set.of("PATCH", "COMMENT").contains(type) || !byStep.containsKey(step)
                    || !"COMPLETE".equals(byStep.get(step).get("status"))) throw invalidAi();
            var saved = new LinkedHashMap<String, Object>();
            saved.put("type", type); saved.put("step", step);
            saved.put("explanation", text(suggestion.get("explanation"), 10000));
            saved.put("sources", sources(suggestion.get("sources"), byStep.get(step), refs));
            Object category = suggestion.get("category"), severity = suggestion.get("severity");
            // Legacy model responses have neither field. New classifications must be a complete pair.
            if (category != null || severity != null) {
                if (!(category instanceof String c) || !Set.of("OMISSION", "CONSISTENCY", "READABILITY", "REJECTION_FIX").contains(c)
                        || !(severity instanceof String s) || !Set.of("MAJOR", "MINOR").contains(s)) throw invalidAi();
                if ("REJECTION_FIX".equals(c) && !"RERUN".equals(input.get("mode"))) throw invalidAi();
                saved.put("category", category); saved.put("severity", severity);
            }
            if ("PATCH".equals(type)) {
                if (!(suggestion.get("original_text") instanceof String original)
                        || original.isEmpty() || original.length() > 100000) throw invalidAi();
                if (!(suggestion.get("replacement_text") instanceof String replacement) || replacement.length() > 100000) throw invalidAi();
                int start = content.indexOf(original), end = start + original.length();
                if (start < 0 || start != content.lastIndexOf(original)
                        || ranges.stream().anyMatch(range -> start < range[1] && range[0] < end)) throw invalidAi();
                ranges.add(new int[] {start, end});
                saved.put("original_text", original); saved.put("replacement_text", replacement);
            } else if (suggestion.get("original_text") != null || suggestion.get("replacement_text") != null
                    || suggestion.get("decision") != null) throw invalidAi();
            suggestions.add(saved);
        }
        result.put("contract_version", 2);
        result.put("review_status", status);
        result.put("steps", STEPS.stream().map(byStep::get).toList());
        result.put("references_used", List.copyOf(used));
        result.put("unavailable_references", STEPS.stream().filter(unavailable::contains).toList());
        result.put("suggestions", suggestions);
        try { canonical(result); }
        catch (ApiException malformed) { throw invalidAi(); }
        return result;
    }

    private static Map<String, Map<?, ?>> references(Map<String, Object> input) {
        var refs = new HashMap<String, Map<?, ?>>();
        if (!(input.get("references") instanceof List<?> list)) throw invalidAi();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> ref) || !"APPROVED".equals(ref.get("status"))
                    || refs.put(text(ref.get("reference_id"), 128), ref) != null) throw invalidAi();
        }
        return refs;
    }

    private static List<Map<String, Object>> sources(Object value, Map<String, Object> step,
            Map<String, Map<?, ?>> refs) {
        if (!(value instanceof List<?> entries) || entries.size() > refs.size()) throw invalidAi();
        var result = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> source)) throw invalidAi();
            String id = text(source.get("reference_id"), 128);
            if (!ids(step.get("reference_ids")).contains(id) || !seen.add(id)) throw invalidAi();
            Map<?, ?> ref = refs.get(id);
            if (ref == null) throw invalidAi();
            var saved = new LinkedHashMap<String, Object>();
            for (String field : List.of("reference_id", "document_id", "document_version_id", "kind", "version_no")) {
                saved.put(field, ref.get(field));
            }
            if (source.get("quote") != null) {
                String quote = text(source.get("quote"), 10000);
                if (!ref.get("content").toString().contains(quote)) throw invalidAi();
                saved.put("quote", quote);
            }
            result.add(saved);
        }
        if (!"BODY".equals(step.get("step")) && result.isEmpty()) throw invalidAi();
        return result;
    }

    private static List<String> ids(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 100) throw invalidAi();
        var result = new ArrayList<String>();
        for (Object item : list) {
            String id = text(item, 128);
            if (result.contains(id)) throw invalidAi();
            result.add(id);
        }
        return result.stream().sorted().toList();
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw invalidAi();
        return text;
    }

    public static ApiException invalidAi() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "invalid_ai_output", "비비 검토 결과 형식이 올바르지 않습니다.");
    }

    // ---- DOC-A22 대화(DISCUSS) · DOC-A23 직접 요청(REQUEST) 응답 검증 (AI 확장 계약은 양측 합의 전 제안 상태) ----

    /** 응답 봉투와 식별값(document_id·revision·content_hash·reference_hash·request_key)을 검증하고 result 를 돌려준다. */
    private static Map<?, ?> identified(Map<String, Object> envelope, Map<String, Object> input) {
        if (envelope == null || !"MODEL".equals(envelope.get("origin"))
                || !(envelope.get("result") instanceof Map<?, ?> raw)
                || !(envelope.get("usage") instanceof List<?> usage) || usage.isEmpty()
                || usage.stream().noneMatch(item -> item instanceof Map<?, ?> u
                        && Boolean.FALSE.equals(u.get("failed"))
                        && u.get("model") instanceof String model && !model.isBlank())) throw invalidAi();
        for (String field : List.of("document_id", "revision", "content_hash", "reference_hash", "request_key")) {
            if ("revision".equals(field)) {
                if (!(raw.get(field) instanceof Number n) || n.doubleValue() != n.longValue()
                        || n.longValue() != ((Number) input.get(field)).longValue()) throw invalidAi();
            } else if (!Objects.equals(input.get(field), raw.get(field))) throw invalidAi();
        }
        return raw;
    }

    /** DISCUSS: {reply:{text, proposed_text?}}. 본문은 바꾸지 않으므로 제안은 없어야 한다. */
    public static Map<String, Object> validateReply(Map<String, Object> envelope, Map<String, Object> input) {
        var raw = identified(envelope, input);
        if (raw.get("suggestions") instanceof List<?> list && !list.isEmpty()) throw invalidAi();
        if (!(raw.get("reply") instanceof Map<?, ?> reply)) throw invalidAi();
        var saved = new LinkedHashMap<String, Object>();
        saved.put("text", text(reply.get("text"), 10000));
        if (reply.get("proposed_text") != null) {
            // 빈 문자열은 "그 부분을 지운다"는 대체문이라 허용한다.
            // COMMENT 는 반영(APPLY) 대상이 아니라 대체문을 쓸 곳이 없다.
            if (input.get("suggestion") instanceof Map<?, ?> target && "COMMENT".equals(target.get("type"))) throw invalidAi();
            if (!(reply.get("proposed_text") instanceof String proposed) || proposed.length() > 100000) throw invalidAi();
            saved.put("proposed_text", proposed);
        }
        return saved;
    }

    /**
     * REQUEST 가 "고칠 곳이 없다"고 답했는지. 제안이 하나도 없고(outcome NO_CHANGE 이거나 생략) 이유(summary)가 있으면 그 이유를 돌려주고,
     * 제안이 있으면 null 이다(그때는 {@link #validateRequested} 가 검증한다). 제안이 없는데 이유가 없거나 outcome 이 어긋나면 502 invalid_ai_output.
     */
    public static String requestedNoChangeReason(Map<String, Object> envelope, Map<String, Object> input) {
        var raw = identified(envelope, input);
        if (raw.get("suggestions") instanceof List<?> entries && !entries.isEmpty()) {
            if ("NO_CHANGE".equals(raw.get("outcome"))) throw invalidAi();
            return null;
        }
        if (raw.get("suggestions") != null && !(raw.get("suggestions") instanceof List<?>)) throw invalidAi();
        if (raw.get("outcome") != null && !"NO_CHANGE".equals(raw.get("outcome"))) throw invalidAi();
        return text(raw.get("summary"), 1000);
    }

    /**
     * REQUEST: 제안 정확히 1개. 검토 때 정한 단계(완료된 것)와 출처 규칙을 그대로 따르고, 원문은 지금 본문에서 한 곳과만 일치하며
     * 아직 처리하지 않은 다른 제안의 구간과 겹치지 않아야 한다. 겹침 구간은 호출한 쪽이 reserved 로 넘긴다.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> validateRequested(Map<String, Object> envelope, Map<String, Object> input,
            Map<String, Object> reviewResult, List<int[]> reserved) {
        var raw = identified(envelope, input);
        if (!(raw.get("suggestions") instanceof List<?> entries) || entries.size() != 1
                || !(entries.getFirst() instanceof Map<?, ?> suggestion)) throw invalidAi();
        var byStep = new HashMap<String, Map<String, Object>>();
        if (!(reviewResult.get("steps") instanceof List<?> steps)) throw invalidAi();
        for (Object step : steps) {
            var map = (Map<String, Object>) step;
            byStep.put(map.get("step").toString(), map);
        }
        String step = text(suggestion.get("step"), 32);
        if (!"PATCH".equals(suggestion.get("type")) || !byStep.containsKey(step)
                || !"COMPLETE".equals(byStep.get(step).get("status"))) throw invalidAi();
        var saved = new LinkedHashMap<String, Object>();
        saved.put("type", "PATCH"); saved.put("step", step);
        saved.put("explanation", text(suggestion.get("explanation"), 10000));
        saved.put("sources", sources(suggestion.get("sources"), byStep.get(step), references(input)));
        if (!(suggestion.get("original_text") instanceof String original) || original.isEmpty() || original.length() > 100000
                || !(suggestion.get("replacement_text") instanceof String replacement) || replacement.length() > 100000) throw invalidAi();
        String content = input.get("content").toString();
        int start = content.indexOf(original), end = start + original.length();
        if (start < 0 || start != content.lastIndexOf(original)
                || reserved.stream().anyMatch(range -> start < range[1] && range[0] < end)) throw invalidAi();
        saved.put("original_text", original); saved.put("replacement_text", replacement);
        try { canonical(saved); } catch (ApiException malformed) { throw invalidAi(); }
        return saved;
    }

    private DocumentReviewResultValidator() { }
}
