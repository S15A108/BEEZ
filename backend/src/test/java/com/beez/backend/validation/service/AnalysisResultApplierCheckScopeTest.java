package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskCompletionReportRepository;
import com.beez.backend.task.service.ProjectDesignLookup;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import com.beez.backend.task.service.DeltaCompletion;
import com.beez.backend.task.service.ValidationStatusLookup;
import com.beez.backend.validation.repository.ValidationRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;

/** Each finding may cite only sources and evidence belonging to its own check. */
class AnalysisResultApplierCheckScopeTest {
    private final AnalysisResultApplier applier = new AnalysisResultApplier(mock(ValidationRepository.class),
            mock(ValidationResponses.class), mock(ProjectDesignLookup.class), mock(ValidationRules.class),
            mock(TaskPlanRevisionRepository.class), mock(TaskPlanDesignRefRepository.class),
            mock(ValidationInputAssembler.class), mock(RelatedTaskCandidateFinder.class), mock(JdbcClient.class), false,
            mock(DeltaCompletion.class), mock(TaskCompletionReportRepository.class), mock(ValidationStatusLookup.class), mock(org.springframework.context.ApplicationEventPublisher.class),
            mock(ValidationActivities.class));

    @Test void rejectsOtherChecksSourceEvenWhenEvidenceIdsAreEmpty() {
        var sourceA = Map.of("document_id", "1", "document_version_id", "11", "version_no", 1, "location", "A");
        var sourceB = Map.of("document_id", "2", "document_version_id", "22", "version_no", 1, "location", "B");
        Map<String, Object> request = Map.of(
                "checks", List.of(
                        Map.of("key", "check-a", "design_item_version_ids", List.of("101"), "related_plan_revision_ids", List.of()),
                        Map.of("key", "check-b", "design_item_version_ids", List.of("202"), "related_plan_revision_ids", List.of())),
                "approved_items", List.of(Map.of("id", "101", "source", sourceA), Map.of("id", "202", "source", sourceB)),
                "evidence", List.of(Map.of("evidence_id", "e-b", "design_item_version_id", "202", "source", sourceB)),
                "related_plans", List.of());
        var matching = Map.of("findings", List.of(Map.of("check_key", "check-a", "design_item_version_id", "101",
                "sources", List.of(sourceA), "evidence_ids", List.of())));
        var wrongSource = Map.of("findings", List.of(Map.of("check_key", "check-a", "design_item_version_id", "101",
                "sources", List.of(sourceB), "evidence_ids", List.of())));
        var wrongEvidence = Map.of("findings", List.of(Map.of("check_key", "check-a", "design_item_version_id", "101",
                "sources", List.of(), "evidence_ids", List.of("e-b"))));

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, matching)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, wrongSource)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, wrongEvidence)).isFalse();
    }

    @Test void multiDesignCheckRequiresCitationsToMatchTheFindingVersion() {
        var sourceA = Map.of("document_id", "1", "document_version_id", "11", "version_no", 1, "location", "A");
        var sourceB = Map.of("document_id", "2", "document_version_id", "22", "version_no", 1, "location", "B");
        Map<String, Object> request = Map.of(
                "checks", List.of(Map.of("key", "completion-delta", "design_item_version_ids", List.of("101", "202"),
                        "related_plan_revision_ids", List.of())),
                "approved_items", List.of(Map.of("id", "101", "source", sourceA), Map.of("id", "202", "source", sourceB)),
                "evidence", List.of(Map.of("evidence_id", "e-b", "design_item_version_id", "202", "source", sourceB)),
                "related_plans", List.of());
        var wrongSource = Map.of("findings", List.of(Map.of("check_key", "completion-delta", "design_item_version_id", "101",
                "sources", List.of(sourceB), "evidence_ids", List.of())));
        var wrongEvidence = Map.of("findings", List.of(Map.of("check_key", "completion-delta", "design_item_version_id", "101",
                "sources", List.of(sourceA), "evidence_ids", List.of("e-b"))));
        var matching = Map.of("findings", List.of(Map.of("check_key", "completion-delta", "design_item_version_id", "202",
                "sources", List.of(sourceB), "evidence_ids", List.of("e-b"))));

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, matching)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, wrongSource)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, wrongEvidence)).isFalse();
    }

    @Test void documentOnlyEvidenceIsScopedToPlanCompletenessAndCannotBeAttributedToADesign() {
        var source = Map.of("document_id", "2", "document_version_id", "22", "version_no", 1, "location", "1~2행");
        var request = Map.<String, Object>of(
                "checks", List.of(
                        Map.of("key", "plan-completeness", "kind", "PLAN_COMPLETENESS", "design_item_version_ids", List.of(), "related_plan_revision_ids", List.of()),
                        Map.of("key", "design", "kind", "DESIGN_ALIGNMENT", "design_item_version_ids", List.of("101"), "related_plan_revision_ids", List.of())),
                "approved_items", List.of(Map.of("id", "101")),
                "evidence", List.of(Map.of("evidence_id", "doc", "source", source, "text", "정책: 미정")),
                "related_plans", List.of());
        var matching = Map.of("findings", List.of(Map.of("check_key", "plan-completeness", "sources", List.of(source), "evidence_ids", List.of("doc"))));
        var wrongDesign = Map.of("findings", List.of(Map.of("check_key", "design", "design_item_version_id", "101", "sources", List.of(source), "evidence_ids", List.of("doc"))));
        var missingPolicy = Map.of("findings", List.of(Map.of("kind", "MISSING_POLICY", "check_key", "plan-completeness", "sources", List.of(source), "evidence_ids", List.of("doc"))));
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, matching)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "findingsMatchChecks", request, wrongDesign)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(applier, "missingPoliciesSupported", request, missingPolicy)).isFalse();
    }
}
