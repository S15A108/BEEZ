package com.beez.backend.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TaskBreakdownDraftTest {
    private static final Map<String, String> VERSIONS = Map.of("10", "20");

    @Test
    void resolvesDependenciesBeforeTheirConsumers() {
        List<TaskBreakdownDraft> drafts = TaskBreakdownDraft.readInDependencyOrder(
                reply(draft("ui", List.of("api"), "frontend", "20"), draft("api", List.of(), null, "20")),
                VERSIONS, Set.of("frontend"));

        assertThat(drafts).extracting(TaskBreakdownDraft::key).containsExactly("api", "ui");
        assertThat(drafts.getLast().toPlan(Map.of("api", "42")).dependencyTaskIds()).containsExactly("42");
    }

    @Test
    void rejectsUnknownOrCyclicDependencies() {
        Map<String, Object> cycle = reply(draft("a", List.of("b"), null, "20"), draft("b", List.of("a"), null, "20"));
        Map<String, Object> missing = reply(draft("a", List.of("missing"), null, "20"));

        for (Map<String, Object> invalid : List.of(cycle, missing)) {
            assertThatThrownBy(() -> TaskBreakdownDraft.readInDependencyOrder(invalid, VERSIONS, Set.of()))
                    .isInstanceOf(ApiException.class);
        }
    }

    @Test
    void rejectsInventedAreasAndMismatchedVersions() {
        Map<String, Object> unrelated = reply(draft("api", List.of(), "finance", "20"));
        Map<String, Object> stale = reply(draft("api", List.of(), "backend", "99"));

        for (Map<String, Object> invalid : List.of(unrelated, stale)) {
            assertThatThrownBy(() -> TaskBreakdownDraft.readInDependencyOrder(invalid, VERSIONS, Set.of("backend")))
                    .isInstanceOf(ApiException.class);
        }
    }

    private static Map<String, Object> reply(Object... drafts) {
        return Map.of("result", Map.of("tasks", List.of(drafts)));
    }

    private static Map<String, Object> draft(String key, List<String> dependencies, String area, String version) {
        Map<String, Object> draft = new LinkedHashMap<>(Map.of(
                "key", key, "title", key, "goal", "Implement approved design", "approach", "Follow contract",
                "included_scope", List.of("feature"), "excluded_scope", List.of(), "completion_criteria", List.of("Tests pass"),
                "dependency_keys", dependencies, "design_refs", List.of(Map.of(
                        "design_item_id", "10", "design_item_version_id", version, "relation", "IMPLEMENTS"))));
        draft.put("assignment_area", area);
        return draft;
    }
}
