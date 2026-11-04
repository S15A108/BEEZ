package com.beez.backend.validation.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Independently bind model policy candidates to the exact authorized AI-A02 input. */
final class PolicyAnalysisContract {
    private static final String KEY = ValidationRules.POLICY_CHECK;
    private static final Set<String> PLAN_FIELDS = Set.of("title", "goal", "included_scope", "approach", "completion_criteria");
    private static final Set<String> STATES = Set.of("SUPPORTED", "EXPLICITLY_UNDECIDED", "CONTRADICTORY", "INSUFFICIENT");
    private static final Pattern UNDECIDED = Pattern.compile(
            "^[ \\t]*(?:[-*][ \\t]*)?[^\\r\\n:：]{0,80}(?:정책|policy)[ \\t]*[:：][ \\t]*"
            + "(?:미정|미결정|미확정|결정되지[ \\t]*않음|정해지지[ \\t]*않음|undecided|not[ \\t]+decided|to[ \\t]+be[ \\t]+determined|tbd)[ \\t]*[.!]?[ \\t]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.MULTILINE);

    private PolicyAnalysisContract() {}

    static String violation(Map<String, Object> request, Map<String, Object> result) {
        try {
            validate(request, result);
            return null;
        } catch (InvalidPolicyOutput e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return "잘못된 정책 분석 형식";
        }
    }

    private static void validate(Map<String, Object> request, Map<String, Object> result) {
        List<Map<String, Object>> checks = maps(request.get("checks"));
        List<Map<String, Object>> policyChecks = checks.stream().filter(c -> KEY.equals(c.get("key"))
                || "POLICY_ALIGNMENT".equals(c.get("kind"))).toList();
        List<Map<String, Object>> findings = maps(result.get("findings"));
        if (policyChecks.isEmpty()) {
            require(result.get("policy_assessments") == null && result.get("policy_assessments_truncated") == null
                    && result.get("no_policy_requirements_reason") == null && result.get("no_policy_requirements_evidence") == null,
                    "정책 검사 없이 정책 결과 반환");
            for (var f : findings) require(f.get("policy_need_id") == null && emptyOrNull(f.get("conflicting_design_refs")), "정책 검사 밖의 정책 후보");
            return;
        }
        require(policyChecks.size() == 1 && KEY.equals(policyChecks.getFirst().get("key"))
                && "POLICY_ALIGNMENT".equals(policyChecks.getFirst().get("kind")), "정책 검사 형식 오류");
        var check = policyChecks.getFirst();
        require(strings(check.get("related_plan_revision_ids")).isEmpty(), "정책 검사의 관련 작업 범위 오류");
        Map<String, Map<String, Object>> items = indexed(maps(request.get("approved_items")), "id");
        Map<String, Map<String, Object>> evidence = indexed(maps(request.get("evidence")), "evidence_id");
        List<String> versions = strings(check.get("design_item_version_ids"));
        require(unique(versions) && Set.copyOf(versions).equals(items.keySet()), "정책 검사 설계 범위 오류");
        Map<String, Object> plan = map(map(request.get("plan_revision")).get("plan"));
        List<Map<String, Object>> assessments = maps(result.get("policy_assessments"));
        require(assessments.size() <= 20 && result.get("policy_assessments_truncated") instanceof Boolean,
                "정책 분석 목록 또는 상한 표시 오류");
        boolean truncated = Boolean.TRUE.equals(result.get("policy_assessments_truncated"));
        List<Map<String, Object>> policyUnassessed = maps(result.get("unassessed")).stream()
                .filter(u -> KEY.equals(u.get("check_key"))).toList();
        if (assessments.isEmpty() && !truncated && policyUnassessed.isEmpty()) {
            require(text(result.get("no_policy_requirements_reason"), 2000)
                    && planQuote(plan, result.get("no_policy_requirements_evidence")), "정책 필요 없음 판단에 계획 근거가 없음");
        } else {
            require(result.get("no_policy_requirements_reason") == null && result.get("no_policy_requirements_evidence") == null,
                    "정책 필요 없음 판단과 분석 범위가 충돌");
        }

        Map<String, Map<String, Object>> byNeed = indexed(assessments, "need_id");
        boolean incomplete = truncated;
        for (var a : assessments) {
            require(fields(a, Set.of("need_id", "topic", "plan_evidence", "state", "design_item_version_ids",
                    "approved_content_refs", "evidence_ids", "explanation"), Set.of()), "정책 판단 필드 오류");
            require(text(a.get("need_id"), 80) && text(a.get("topic"), 500) && text(a.get("explanation"), 2000)
                    && planQuote(plan, a.get("plan_evidence")) && STATES.contains(a.get("state")), "정책 필요 항목 형식 또는 계획 근거 오류");
            List<String> scope = strings(a.get("design_item_version_ids"));
            List<String> evIds = strings(a.get("evidence_ids"));
            List<Map<String, Object>> refs = maps(a.get("approved_content_refs"));
            require(scope.size() <= 50 && evIds.size() <= 50 && refs.size() <= 50 && unique(scope) && unique(evIds)
                    && items.keySet().containsAll(scope), "정책 근거 범위 오류");
            for (var ref : refs) {
                require(fields(ref, Set.of("design_item_version_id", "path", "quote"), Set.of()), "승인 근거 필드 오류");
                String version = requiredText(ref.get("design_item_version_id"));
                require(scope.contains(version) && contentQuote(items.get(version), ref), "승인 내용의 경로 또는 인용 오류");
            }
            for (String id : evIds) {
                var ev = evidence.get(id);
                require(ev != null && scope.contains(ev.get("design_item_version_id")), "정책 근거가 지정된 설계에 속하지 않음");
            }
            String state = (String) a.get("state");
            if (Set.of("SUPPORTED", "CONTRADICTORY").contains(state)) require(!refs.isEmpty() || !evIds.isEmpty(), "정책 판단 근거 누락");
            if ("EXPLICITLY_UNDECIDED".equals(state)) require(evIds.stream().anyMatch(id ->
                    evidence.get(id).get("text") instanceof String s && UNDECIDED.matcher(s).find()), "정책 미정 원문 근거 누락");
            if ("INSUFFICIENT".equals(state)) incomplete = true;
        }
        if (incomplete) require(policyUnassessed.size() == 1 && "EVIDENCE_INSUFFICIENT".equals(policyUnassessed.getFirst().get("reason")),
                "불완전한 정책 분석을 완료로 표시");

        Set<String> matched = new HashSet<>(), fingerprints = new HashSet<>();
        for (var f : findings) {
            if (!KEY.equals(f.get("check_key"))) {
                require(f.get("policy_need_id") == null && emptyOrNull(f.get("conflicting_design_refs")), "정책 검사 밖의 정책 후보");
                continue;
            }
            String need = requiredText(f.get("policy_need_id"));
            var a = byNeed.get(need);
            require(a != null && f.get("related_task_id") == null, "정책 후보의 필요 항목 오류");
            String kind = requiredText(f.get("kind")), state = (String) a.get("state");
            require(("EXPLICITLY_UNDECIDED".equals(state) && "MISSING_POLICY".equals(kind))
                    || ("CONTRADICTORY".equals(state) && Set.of("MISMATCH", "CONFLICT").contains(kind)), "정책 후보와 판단 상태 불일치");
            String primary = requiredText(f.get("design_item_version_id"));
            List<String> scope = strings(a.get("design_item_version_ids"));
            List<String> findingEvidence = strings(f.get("evidence_ids"));
            require(scope.contains(primary) && unique(findingEvidence) && strings(a.get("evidence_ids")).containsAll(findingEvidence), "정책 후보의 설계 또는 근거 범위 오류");
            List<Map<String, Object>> refs = maps(a.get("approved_content_refs"));
            List<Map<String, Object>> conflicts = f.get("conflicting_design_refs") == null ? List.of() : maps(f.get("conflicting_design_refs"));
            require(conflicts.size() <= 20 && "CONFLICT".equals(kind) == !conflicts.isEmpty(), "정책 모순의 양쪽 설계 누락");
            Set<String> seenVersions = new HashSet<>();
            seenVersions.add(primary);
            for (var conflict : conflicts) {
                require(fields(conflict, Set.of("design_item_id", "design_item_version_id", "evidence_ids", "content_paths"), Set.of()),
                        "모순 설계 참조 필드 오류");
                String version = requiredText(conflict.get("design_item_version_id"));
                var item = items.get(version);
                require(item != null && Objects.equals(item.get("design_item_id"), conflict.get("design_item_id"))
                        && scope.contains(version) && seenVersions.add(version), "모순 설계 참조 오류");
                List<String> conflictEvidence = strings(conflict.get("evidence_ids"));
                require(conflictEvidence.size() <= 50 && unique(conflictEvidence) && findingEvidence.containsAll(conflictEvidence), "모순 근거 인용 오류");
                for (String id : conflictEvidence) require(version.equals(evidence.get(id).get("design_item_version_id")), "모순 근거의 설계 버전 오류");
                Object pathsValue = conflict.get("content_paths");
                require(pathsValue instanceof List<?>, "모순 내용 경로 형식 오류");
                List<?> paths = (List<?>) pathsValue;
                require(paths.size() <= 50 && (!conflictEvidence.isEmpty() || !paths.isEmpty()), "모순 설계 근거 누락");
                for (Object path : paths) {
                    List<String> parts = strings(path);
                    require(refs.stream().anyMatch(ref -> version.equals(ref.get("design_item_version_id")) && parts.equals(ref.get("path"))), "분석하지 않은 모순 내용 경로");
                }
            }
            require(findingEvidence.stream().anyMatch(id -> primary.equals(evidence.get(id).get("design_item_version_id")))
                    || refs.stream().anyMatch(ref -> primary.equals(ref.get("design_item_version_id"))), "기준 설계 근거 누락");
            var sorted = new ArrayList<>(seenVersions);
            sorted.sort(String::compareTo);
            require(fingerprints.add(need + "\u0000" + kind + "\u0000" + primary + "\u0000" + sorted), "중복 정책 후보");
            matched.add(need);
        }
        if (strings(result.get("checked_keys")).contains(KEY)) {
            for (var a : assessments) if (Set.of("EXPLICITLY_UNDECIDED", "CONTRADICTORY").contains(a.get("state")))
                require(matched.contains(a.get("need_id")), "확인된 정책 문제에 후보가 없음");
        }
    }

    private static boolean planQuote(Map<String, Object> plan, Object value) {
        if (!(value instanceof Map<?, ?>)) return false;
        var ref = map(value);
        if (!fields(ref, Set.of("field", "quote"), Set.of("index"))) return false;
        if (!(ref.get("field") instanceof String field) || !PLAN_FIELDS.contains(field) || !text(ref.get("quote"), 1000)) return false;
        Object target = plan.get(field), index = ref.get("index");
        if (target instanceof List<?> values) {
            if (!(index instanceof Integer) && !(index instanceof Long)) return false;
            long i = ((Number) index).longValue();
            if (i < 0 || i >= values.size()) return false;
            target = values.get((int) i);
        } else if (index != null) return false;
        return target instanceof String s && s.contains((String) ref.get("quote"));
    }

    private static boolean contentQuote(Map<String, Object> item, Map<String, Object> ref) {
        if (item == null || !text(ref.get("quote"), 1000)) return false;
        List<String> path = strings(ref.get("path"));
        if (path.isEmpty() || path.size() > 12) return false;
        Object current = item.get("content");
        for (String part : path) {
            if (part.length() > 256) return false;
            if (current instanceof Map<?, ?> m && m.containsKey(part)) current = m.get(part);
            else if (current instanceof List<?> list && part.matches("0|[1-9][0-9]*")) {
                try {
                    int i = Integer.parseInt(part);
                    if (i >= list.size()) return false;
                    current = list.get(i);
                } catch (NumberFormatException e) { return false; }
            } else return false;
        }
        String quote = (String) ref.get("quote");
        if (current instanceof String s) return s.contains(quote);
        return (current instanceof Boolean || current instanceof Integer || current instanceof Long) && quote.equals(current.toString());
    }

    private static Map<String, Map<String, Object>> indexed(List<Map<String, Object>> values, String key) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (var value : values) require(out.put(requiredText(value.get(key)), value) == null, "중복된 정책 입력 식별자");
        return out;
    }
    private static boolean emptyOrNull(Object value) { return value == null || value instanceof List<?> list && list.isEmpty(); }
    private static boolean text(Object value, int max) { return value instanceof String s && !s.isBlank() && s.length() <= max; }
    private static String requiredText(Object value) { require(text(value, 2000), "문자열 필드 누락"); return (String) value; }
    private static boolean unique(List<String> values) { return new HashSet<>(values).size() == values.size(); }
    private static boolean fields(Map<String, Object> value, Set<String> required, Set<String> optional) {
        Set<String> allowed = new HashSet<>(required);
        allowed.addAll(optional);
        return value.keySet().containsAll(required) && allowed.containsAll(value.keySet());
    }
    private static void require(boolean condition, String error) { if (!condition) throw new InvalidPolicyOutput(error); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) { require(value instanceof Map<?, ?>, "객체 필드 오류"); return (Map<String, Object>) value; }
    private static List<Map<String, Object>> maps(Object value) {
        require(value instanceof List<?>, "객체 배열 오류");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object entry : (List<?>) value) out.add(map(entry));
        return out;
    }
    private static List<String> strings(Object value) {
        require(value instanceof List<?>, "문자열 배열 오류");
        List<String> out = new ArrayList<>();
        for (Object entry : (List<?>) value) out.add(requiredText(entry));
        return out;
    }
    private static final class InvalidPolicyOutput extends RuntimeException {
        InvalidPolicyOutput(String message) { super(message); }
    }
}
