package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.task.service.ProjectDesignLookup.ApprovedItem;
import com.beez.backend.task.service.TaskPlanDraftLimits;
import com.beez.backend.task.service.TaskPlanDraftSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Offline replay of the failed login request (S15P31A108-385): the selector keeps the login excerpts and drops the rest.
 * The input is a sanitized fixture on the test classpath; similarities were not supplied, so this checks word match and
 * table compaction only, not search ranking.
 */
class TaskPlanDraftLoginReplayTest {
    @SuppressWarnings("unchecked")
    @Test
    void keepsTheLoginExcerptsAndDropsUnrelatedContext() throws Exception {
        var json = JsonMapper.builder().build();
        Map<String, Object> input;
        try (var stream = getClass().getResourceAsStream("/task-plan/login-input.json")) {
            assertThat(stream).as("login-input.json on the test classpath").isNotNull();
            input = json.readValue(stream.readAllBytes(), Map.class);
        }
        var excerpts = (List<Map<String, Object>>) input.get("excerpts");
        var items = (List<Map<String, Object>>) input.get("design_items");
        List<ChunkEvidence> candidates = new ArrayList<>();
        for (var excerpt : excerpts) {
            long id = Long.parseLong(excerpt.get("excerpt_id").toString().substring(1));
            candidates.add(new ChunkEvidence(id, 1, 1, 1, "c" + id, excerpt.get("text").toString(),
                    Map.of(), excerpt.get("location").toString()));
        }
        var limits = new TaskPlanDraftLimits(12, 4000, 12000, 100, 300);
        var selected = TaskPlanDraftSelector.excerpts("로그인", candidates, limits);
        var approved = items.stream().map(i -> new ApprovedItem(Long.parseLong(i.get("design_item_id").toString()), 1,
                i.get("key").toString(), i.get("kind").toString(), i.get("summary").toString(), "")).toList();
        var selectedItems = TaskPlanDraftSelector.designItems("로그인", selected, approved, limits.maxDesignItems());
        assertThat(selected).hasSizeLessThan(candidates.size());
        assertThat(String.join("\n", selected.stream().map(ChunkEvidence::text).toList())).contains("/auth/guest", "Access")
                .doesNotContain("/ai/v1/health", "EC2 1대");
        assertThat(selectedItems).isEmpty(); // All 11 supplied design items concern games/AI, not login.
    }
}
