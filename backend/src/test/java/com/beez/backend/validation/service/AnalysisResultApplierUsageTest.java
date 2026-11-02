package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskCompletionReportRepository;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import com.beez.backend.task.service.DeltaCompletion;
import com.beez.backend.validation.repository.ValidationRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

class AnalysisResultApplierUsageTest {
    private static AnalysisResultApplier applier(boolean fakeEnabled) {
        return new AnalysisResultApplier(mock(ValidationRepository.class), mock(ValidationResponses.class),
                mock(ProjectDesignLookup.class), mock(ValidationRules.class), mock(TaskPlanRevisionRepository.class),
                mock(TaskPlanDesignRefRepository.class), mock(ValidationInputAssembler.class),
                mock(RelatedTaskCandidateFinder.class), mock(JdbcClient.class), fakeEnabled, mock(DeltaCompletion.class),
                mock(TaskCompletionReportRepository.class));
    }

    private static boolean valid(AnalysisResultApplier applier, Map<String, Object> response) {
        Map<String, Object> request = Map.of("validation_id", "42", "attempt", 2);
        return Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(applier, "successfulModelUsage", request, response));
    }

    private static Map<String, Object> usage(String key, String model, boolean failed) {
        return Map.of("request_key", key, "feature", "TASK_VALIDATION", "model", model, "failed", failed);
    }

    @Test void acceptsOnlySuccessfulModelUsageForThisValidationAttempt() {
        var applier = applier(false);
        var good = usage("llm-validate-42-attempt-2-call-1", "model-a", false);
        assertThat(valid(applier, Map.of("model", "model-a", "usage", List.of(good)))).isTrue();
        assertThat(valid(applier, Map.of("model", "model-a", "usage", List.of()))).isFalse();
        assertThat(valid(applier, Map.of("model", "model-a", "usage", List.of(
                usage("llm-validate-42-attempt-2-call-1", "model-a", true))))).isFalse();
        assertThat(valid(applier, Map.of("model", "model-a", "usage", List.of(
                usage("llm-validate-43-attempt-2-call-1", "model-a", false))))).isFalse();
        assertThat(valid(applier, Map.of("model", "model-a", "usage", List.of(good, good)))).isFalse();
        assertThat(valid(applier, Map.of("model", "model-b", "usage", List.of(good)))).isFalse();
    }

    @Test void allowsEmptyUsageForFakeAnalyzerOnlyWhenExplicitlyEnabled() {
        Map<String, Object> fake = Map.of("model", FakeValidationAnalyzer.MODEL, "usage", List.of());
        assertThat(valid(applier(false), fake)).isFalse();
        assertThat(valid(applier(true), fake)).isTrue();
        assertThat(valid(applier(true), Map.of("model", "real-model", "usage", List.of()))).isFalse();
    }
}
