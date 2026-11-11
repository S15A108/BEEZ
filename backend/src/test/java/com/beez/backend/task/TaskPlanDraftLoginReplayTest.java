package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.task.service.ProjectDesignLookup.ApprovedItem;
import com.beez.backend.task.service.TaskPlanDraftLimits;
import com.beez.backend.task.service.TaskPlanDraftSelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Offline replay of the user-supplied failed request; similarities were not supplied. */
class TaskPlanDraftLoginReplayTest {
    @SuppressWarnings("unchecked")
    @Test
    void reducesLoginContextAndWritesReproducibleAiInput() throws Exception {
        var json = JsonMapper.builder().build();
        var input = json.readValue(Files.readString(Path.of("../docs/experiments/task-plan-385/login-input.json")), Map.class);
        var excerpts = (List<Map<String, Object>>) input.get("excerpts");
        var items = (List<Map<String, Object>>) input.get("design_items");
        List<ChunkEvidence> candidates = new ArrayList<>();
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (var excerpt : excerpts) {
            long id = Long.parseLong(excerpt.get("excerpt_id").toString().substring(1));
            byId.put(id, excerpt);
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
        List<Map<String, Object>> packed = selected.stream().map(c -> {
            var entry = new LinkedHashMap<>(byId.get(c.chunkId()));
            entry.put("text", c.text());
            return (Map<String, Object>) entry;
        }).toList();
        var output = new LinkedHashMap<>(input);
        output.put("excerpts", packed);
        output.put("design_items", List.of());
        var path = Path.of("build/task-plan-385/login-selected.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, json.writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.out.printf("login-replay excerpts=%d->%d design_items=%d->%d source_chars=%d->%d%n",
                candidates.size(), selected.size(), approved.size(), selectedItems.size(),
                candidates.stream().mapToInt(c -> c.text().length()).sum(), selected.stream().mapToInt(c -> c.text().length()).sum());
    }
}
