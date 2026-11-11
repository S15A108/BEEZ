package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.task.dto.DesignRefInput;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.entity.PlanRelation;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Validates AI output before any task or dependency can be written. */
record TaskBreakdownDraft(String key, String title, String goal, List<String> includedScope,
                          List<String> excludedScope, String approach, List<String> completionCriteria,
                          List<DesignRefInput> designRefs, List<String> dependencyKeys, String assignmentArea) {
    private static final int MAX_TASKS = 24;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,47}");

    TaskPlanInput toPlan(Map<String, String> savedTaskIds) {
        return new TaskPlanInput(title, goal, includedScope, excludedScope, approach, completionCriteria,
                dependencyKeys.stream().map(savedTaskIds::get).toList(), designRefs, List.of(), true, null);
    }

    static List<TaskBreakdownDraft> readInDependencyOrder(Map<String, Object> response, Map<String, String> designVersions,
                                                           Set<String> assignmentAreas) {
        if (response == null || !(response.get("result") instanceof Map<?, ?> result)) throw invalid();
        List<?> tasks = boundedList(result.get("tasks"), 1, MAX_TASKS);
        Map<String, TaskBreakdownDraft> byKey = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Object raw : tasks) {
            if (!(raw instanceof Map<?, ?> item) || !(item.get("key") instanceof String key)
                    || !KEY.matcher(key).matches()) continue;
            if (!seen.add(key)) {
                byKey.remove(key);
                continue;
            }
            try {
                byKey.put(key, readCandidate(key, item, designVersions, assignmentAreas));
            } catch (ApiException ignored) {
                // A malformed candidate leaves its key unavailable to dependents.
            }
        }
        Map<String, TaskBreakdownDraft> ordered = new LinkedHashMap<>();
        Set<String> visiting = new HashSet<>();
        Set<String> rejected = new HashSet<>();
        for (String key : byKey.keySet()) visit(key, byKey, visiting, rejected, ordered);
        if (ordered.isEmpty()) throw invalid();
        return List.copyOf(ordered.values());
    }

    private static TaskBreakdownDraft readCandidate(String key, Map<?, ?> item, Map<String, String> designVersions,
                                                     Set<String> assignmentAreas) {
        String area = item.get("assignment_area") == null ? null : text(item.get("assignment_area"), 100);
        if (area != null && !assignmentAreas.contains(area)) throw invalid();
        List<String> dependencies = boundedList(item.get("dependency_keys"), 0, MAX_TASKS - 1).stream()
                .map(TaskBreakdownDraft::key).toList();
        if (new HashSet<>(dependencies).size() != dependencies.size()) throw invalid();
        return new TaskBreakdownDraft(key, text(item.get("title"), 200), text(item.get("goal"), 3000),
                textList(item.get("included_scope"), 1), textList(item.get("excluded_scope"), 0),
                text(item.get("approach"), 6000), textList(item.get("completion_criteria"), 1),
                refs(item.get("design_refs"), designVersions), dependencies, area);
    }

    private static boolean visit(String key, Map<String, TaskBreakdownDraft> byKey, Set<String> visiting,
                                 Set<String> rejected, Map<String, TaskBreakdownDraft> ordered) {
        if (ordered.containsKey(key)) return true;
        if (rejected.contains(key) || !visiting.add(key)) return false;
        TaskBreakdownDraft draft = byKey.get(key);
        boolean valid = draft != null && draft.dependencyKeys().stream()
                .allMatch(dependency -> visit(dependency, byKey, visiting, rejected, ordered));
        visiting.remove(key);
        if (valid) {
            ordered.put(key, draft);
        } else {
            rejected.add(key);
        }
        return valid;
    }

    private static List<DesignRefInput> refs(Object value, Map<String, String> designVersions) {
        List<DesignRefInput> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object raw : boundedList(value, 1, 20)) {
            if (!(raw instanceof Map<?, ?> ref)) throw invalid();
            String itemId = text(ref.get("design_item_id"), 19);
            String versionId = text(ref.get("design_item_version_id"), 19);
            String relation = text(ref.get("relation"), 10);
            if (!versionId.equals(designVersions.get(itemId)) || !seen.add(itemId)
                    || (!relation.equals("READS") && !relation.equals("IMPLEMENTS"))) throw invalid();
            result.add(new DesignRefInput(itemId, versionId, PlanRelation.valueOf(relation), null));
        }
        return result;
    }

    private static List<String> textList(Object value, int min) {
        return boundedList(value, min, 12).stream().map(item -> text(item, 500)).toList();
    }

    private static List<?> boundedList(Object value, int min, int max) {
        if (!(value instanceof List<?> list) || list.size() < min || list.size() > max) throw invalid();
        return list;
    }

    private static String key(Object value) {
        if (!(value instanceof String key) || !KEY.matcher(key).matches()) throw invalid();
        return key;
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String text) || text.isBlank() || text.codePointCount(0, text.length()) > max
                || text.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(text)) throw invalid();
        return text.strip();
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "ai_invalid_output", "생성한 작업의 설계 참조나 선행 관계를 확인할 수 없습니다. 다시 시도해 주세요.");
    }
}
