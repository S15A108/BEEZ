package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PolicyAnalysisContractTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private Map<String, Object> request;
    private Map<String, Object> result;

    @BeforeEach
    void setUp() {
        request = json("""
                {"checks":[{"key":"policy-gap-v1","kind":"POLICY_ALIGNMENT","design_item_version_ids":["311","312"],"related_plan_revision_ids":[]}],
                 "plan_revision":{"plan":{"goal":"로그인 실패 시 잠금 정책을 적용한다"}},
                 "approved_items":[{"id":"311","design_item_id":"301","content":{"description":"5회 실패 시 30분 잠금"}},
                                   {"id":"312","design_item_id":"302","content":{"description":"3회 실패 시 10분 잠금"}}],
                 "evidence":[]}
                """);
        result = json("""
                {"analysis_status":"COMPLETE","checked_keys":["policy-gap-v1"],"unassessed":[],
                 "policy_assessments_truncated":false,
                 "policy_assessments":[{"need_id":"lock","topic":"잠금","plan_evidence":{"field":"goal","quote":"잠금 정책"},
                  "state":"CONTRADICTORY","design_item_version_ids":["311","312"],
                  "approved_content_refs":[{"design_item_version_id":"311","path":["description"],"quote":"5회 실패"},
                                           {"design_item_version_id":"312","path":["description"],"quote":"3회 실패"}],
                  "evidence_ids":[],"explanation":"잠금 조건이 서로 다릅니다"}],
                 "findings":[{"check_key":"policy-gap-v1","kind":"CONFLICT","severity":"BLOCKING",
                  "policy_need_id":"lock","design_item_id":"301","design_item_version_id":"311","evidence_ids":[],
                  "conflicting_design_refs":[{"design_item_id":"302","design_item_version_id":"312","evidence_ids":[],"content_paths":[["description"]]}]}]}
                """);
    }

    @Test
    void acceptsConflictOnlyWithBothExactInputReferences() {
        assertThat(PolicyAnalysisContract.violation(request, result)).isNull();
        conflict().put("design_item_id", "999");
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @Test
    void rejectsPlanAndDesignQuotesThatAreNotInTheRequest() {
        map(assessment().get("plan_evidence")).put("quote", "없는 계획 문장");
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
        setUp();
        map(list(assessment().get("approved_content_refs")).getFirst()).put("quote", "없는 설계 문장");
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @Test
    void rejectsUnassessedAndTruncatedResultsPresentedAsComplete() {
        assessment().put("state", "INSUFFICIENT");
        result.put("findings", List.of());
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
        result.put("checked_keys", List.of());
        result.put("unassessed", List.of(Map.of("check_key", "policy-gap-v1", "reason", "EVIDENCE_INSUFFICIENT")));
        assertThat(PolicyAnalysisContract.violation(request, result)).isNull();
        setUp();
        result.put("policy_assessments_truncated", true);
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @Test
    void rejectsForgedConflictPathAndMissingProblemFinding() {
        conflict().put("content_paths", List.of(List.of("unknown")));
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
        setUp();
        result.put("findings", List.of());
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @Test
    void rejectsMissingPolicyFieldsAndMissingDesignScope() {
        result.remove("policy_assessments");
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
        setUp();
        map(list(request.get("checks")).getFirst()).put("design_item_version_ids", List.of("311"));
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @Test
    void rejectsUnknownFieldsInsideAConflictingReference() {
        conflict().put("unverified", "extra model output");
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @Test
    void rejectsUndecidedUnlessOriginalEvidenceExplicitlySaysSo() {
        assessment().put("state", "EXPLICITLY_UNDECIDED");
        assessment().put("design_item_version_ids", List.of("311"));
        assessment().put("approved_content_refs", List.of(map(list(assessment().get("approved_content_refs")).getFirst())));
        assessment().put("evidence_ids", List.of("e1"));
        request.put("evidence", List.of(Map.of("evidence_id", "e1", "design_item_version_id", "311", "text", "잠금 정책: 미정")));
        var finding = map(list(result.get("findings")).getFirst());
        finding.put("kind", "MISSING_POLICY");
        finding.put("evidence_ids", List.of("e1"));
        finding.put("conflicting_design_refs", List.of());
        assertThat(PolicyAnalysisContract.violation(request, result)).isNull();
        request.put("evidence", List.of(Map.of("evidence_id", "e1", "design_item_version_id", "311", "text", "잠금 정책: 미정이 아니다")));
        assertThat(PolicyAnalysisContract.violation(request, result)).isNotNull();
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> json(String value) { return JSON.readValue(value, Map.class); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private Map<String, Object> assessment() { return map(list(result.get("policy_assessments")).getFirst()); }
    private Map<String, Object> conflict() { return map(list(map(list(result.get("findings")).getFirst()).get("conflicting_design_refs")).getFirst()); }
}
