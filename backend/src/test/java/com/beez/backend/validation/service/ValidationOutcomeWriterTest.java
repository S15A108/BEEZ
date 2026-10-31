package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.auth.CallerContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ValidationOutcomeWriterTest {
    private final ValidationOutcomeWriter.Execution execution = new ValidationOutcomeWriter.Execution(
            1, 2, 3, 4, 1, UUID.randomUUID(), 5, 6, Map.of(8L, 9L), "gms", CallerContext.human(1L));

    private Map<String, Object> source(String documentId) {
        return Map.of("document_id", documentId, "document_version_id", "20", "version_no", 1, "location", "API");
    }

    private Map<String, Object> request() {
        var request = new HashMap<String, Object>();
        request.put("project_id", "1"); request.put("validation_id", "3"); request.put("attempt", 1);
        request.put("plan_revision", Map.of("id", "6", "task_id", "2"));
        request.put("unresolved", List.of());
        request.put("approved_items", List.of(Map.of("id", "11", "design_item_id", "10", "source", source("19")),
                Map.of("id", "13", "design_item_id", "12", "source", source("21"))));
        request.put("related_plans", List.of(Map.of("id", "9", "task_id", "8")));
        request.put("checks", List.of(Map.of("key", "api", "design_item_version_ids", List.of("11"), "related_plan_revision_ids", List.of()),
                Map.of("key", "other", "design_item_version_ids", List.of("13"), "related_plan_revision_ids", List.of("9"))));
        request.put("evidence", List.of());
        return request;
    }

    private Map<String, Object> finding() {
        var f = new HashMap<String, Object>();
        f.put("check_key", "api"); f.put("kind", "MISMATCH"); f.put("severity", "BLOCKING");
        f.put("design_item_id", "10"); f.put("design_item_version_id", "11");
        f.put("message", "필드 불일치"); f.put("suggested_action", "계획 확인");
        f.put("sources", List.of(source("19"))); f.put("evidence_ids", List.of());
        return f;
    }

    private Map<String, Object> result() {
        var r = new HashMap<String, Object>();
        r.put("analysis_status", "COMPLETE"); r.put("checked_keys", List.of("api", "other"));
        r.put("unassessed", List.of()); r.put("findings", List.of(finding())); return r;
    }

    @Test void validBoundedFindingIsAccepted() {
        assertThatCode(() -> ValidationOutcomeWriter.verify(execution, request(), result())).doesNotThrowAnyException();
    }

    @Test void missingOrDuplicateCheckIsRejected() {
        var r = result(); r.put("checked_keys", List.of("api"));
        assertThatThrownBy(() -> ValidationOutcomeWriter.verify(execution, request(), r)).isInstanceOf(IllegalArgumentException.class);
        r.put("checked_keys", List.of("api", "api", "other"));
        assertThatThrownBy(() -> ValidationOutcomeWriter.verify(execution, request(), r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unrelatedSourcesAndUnprovidedTaskAreRejected() {
        var f = finding(); f.put("sources", List.of(source("21")));
        var r = result(); r.put("findings", List.of(f));
        assertThatThrownBy(() -> ValidationOutcomeWriter.verify(execution, request(), r)).isInstanceOf(IllegalArgumentException.class);
        f.put("sources", List.of(source("19"))); f.put("related_task_id", "8");
        assertThatThrownBy(() -> ValidationOutcomeWriter.verify(execution, request(), r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void unresolvedAndUnprovedPolicyCannotBecomeComplete() {
        var request = request(); request.put("unresolved", List.of("권한 정책 미정"));
        assertThatThrownBy(() -> ValidationOutcomeWriter.verify(execution, request, result())).isInstanceOf(IllegalArgumentException.class);
        var f = finding(); f.put("kind", "MISSING_POLICY");
        var result = result(); result.put("findings", List.of(f));
        assertThatThrownBy(() -> ValidationOutcomeWriter.verify(execution, request(), result)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void reviewTakesPriorityOverMissingInput() {
        var missing = Map.<String, Object>of("severity", "BLOCKING", "kind", "MISSING_INPUT");
        var conflict = Map.<String, Object>of("severity", "BLOCKING", "kind", "CONFLICT");
        assertThat(ValidationOutcomeWriter.classify(List.of(missing), List.of())).isEqualTo("NEEDS_INPUT");
        assertThat(ValidationOutcomeWriter.classify(List.of(missing), List.of(conflict))).isEqualTo("NEEDS_REVIEW");
        assertThat(ValidationOutcomeWriter.classify(List.of(), List.of())).isEqualTo("PASS");
    }
}
