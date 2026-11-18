package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.task.service.TaskPlanDraftLimits;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TaskPlanDraftLimitsTest {
    @Test
    void bindsOperationalBudgetsAndPreservesDefaultsForUnsetValues() {
        var source = new MapConfigurationPropertySource(Map.of(
                "beez.task-plan-draft.retrieval-limit", "5",
                "beez.task-plan-draft.max-excerpt-chars", "1000",
                "beez.task-plan-draft.total-excerpt-chars", "3000",
                "beez.task-plan-draft.max-design-items", "20"));
        var limits = new Binder(source).bind("beez.task-plan-draft", Bindable.of(TaskPlanDraftLimits.class)).get();
        assertThat(limits.retrievalLimit()).isEqualTo(5);
        assertThat(limits.maxExcerptChars()).isEqualTo(1000);
        assertThat(limits.totalExcerptChars()).isEqualTo(3000);
        assertThat(limits.maxDesignItems()).isEqualTo(20);
        assertThat(limits.designSummaryChars()).isEqualTo(300);
    }

    @Test
    void rejectsBudgetsOutsideTheAiContract() {
        assertThatThrownBy(() -> new TaskPlanDraftLimits(13, 4000, 12000, 100, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskPlanDraftLimits(12, 4000, 3000, 100, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TaskPlanDraftLimits(12, 4000, 12000, -1, 300))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
