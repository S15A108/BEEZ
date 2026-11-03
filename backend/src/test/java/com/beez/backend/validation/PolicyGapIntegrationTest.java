package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Real API, worker, persistence and PostgreSQL; synthetic search/model boundaries. */
@TestPropertySource(properties = "beez.validation.policy-analysis-enabled=true")
class PolicyGapIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @MockitoBean HybridDocumentCandidateSearch search;
    @MockitoBean ValidationAnalyzer analyzer;
    private TaskApiSupport api;
    private long project, owner, baseline;

    @BeforeEach
    void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); owner = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p").param("p", project).query(Long.class).single();
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false));
    }

    @Test
    void noDesignPlanQueuesPolicyCheckAndReturnsAuditableResult() throws Exception {
        long validation = accepted();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            assertThat(checks(request)).anySatisfy(c -> assertThat(c).containsEntry("key", "policy-gap-v1"));
            Map<String, Object> result = result(request, List.of(), List.of());
            result.put("policy_assessments", List.of());
            result.put("policy_assessments_truncated", false);
            result.put("no_policy_requirements_reason", "문서 맞춤법만 수정합니다");
            result.put("no_policy_requirements_evidence", Map.of("field", "goal", "quote", "목표"));
            return response(validation, result);
        });
        assertThat(worker.runOnce()).isTrue();
        var response = api.get("/api/projects/" + project + "/validations/" + validation, owner);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var body = JSON.readTree(response.body());
        assertThat(body.get("status").asText()).isEqualTo("PASS");
        assertThat(body.get("policy_analysis").get("policy_assessments").size()).isZero();
        assertThat(api.jdbc.sql("select rule_version from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("rules-v3-policy-v1");
    }

    @Test
    void readsOnlyPlanAlsoQueuesWhenPolicyAnalysisIsEnabled() {
        long validation = accepted(api.readsRef("API-01"));
        assertThat(api.jdbc.sql("select status from validation where id=:v").param("v", validation)
                .query(String.class).single()).isEqualTo("RUNNING");
        assertThat(api.jdbc.sql("select summary::text from validation where id=:v").param("v", validation)
                .query(String.class).single()).contains("policy-gap-v1");
    }

    @Test
    void previouslyQueuedValidationKeepsItsOriginalCheckVersion() throws Exception {
        long validation = accepted(api.implementsRef("API-01"));
        // A job accepted before the flag existed has no pinned policy version.
        api.jdbc.sql("update validation set rule_version='rules-v2', summary=summary - 'policy_analysis_version' where id=:v")
                .param("v", validation).update();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            assertThat(checks(request)).noneMatch(c -> "policy-gap-v1".equals(c.get("key")));
            var unassessed = checks(request).stream().map(c -> Map.<String, Object>of(
                    "check_key", c.get("key"), "reason", "EVIDENCE_INSUFFICIENT", "message", "근거 부족"))
                    .toList();
            return response(validation, result(request, List.of(), unassessed));
        });
        assertThat(worker.runOnce()).isTrue();
        verify(analyzer).analyze(any());
        var body = JSON.readTree(api.get("/api/projects/" + project + "/validations/" + validation, owner).body());
        assertThat(body.get("status").asText()).isEqualTo("ERROR");
        assertThat(body.has("policy_analysis")).isFalse();
    }

    @Test
    void pinnedPolicyCheckCannotBeDroppedFromAnExecution() throws Exception {
        long validation = accepted();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            checks(request).removeIf(c -> "policy-gap-v1".equals(c.get("key")));
            return response(validation, result(request, List.of(), List.of()));
        });
        assertThat(worker.runOnce()).isTrue();
        var body = JSON.readTree(api.get("/api/projects/" + project + "/validations/" + validation, owner).body());
        assertThat(body.get("status").asText()).isEqualTo("ERROR");
        assertThat(body.get("error_code").asText()).isEqualTo("ai_invalid_output");
    }

    @Test
    void missingEvidenceIsExposedAsPartialAndNeverAsMissingPolicy() throws Exception {
        long validation = accepted();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            Map<String, Object> result = result(request, List.of(), List.of(Map.of(
                    "check_key", "policy-gap-v1", "reason", "EVIDENCE_INSUFFICIENT", "message", "승인 근거 부족")));
            result.put("policy_assessments", List.of(Map.of("need_id", "p1", "topic", "정책", "state", "INSUFFICIENT",
                    "plan_evidence", Map.of("field", "goal", "quote", "목표"), "design_item_version_ids", List.of(),
                    "approved_content_refs", List.of(), "evidence_ids", List.of(), "explanation", "근거가 부족합니다")));
            result.put("policy_assessments_truncated", false);
            return response(validation, result);
        });
        assertThat(worker.runOnce()).isTrue();
        var body = JSON.readTree(api.get("/api/projects/" + project + "/validations/" + validation, owner).body());
        assertThat(body.get("status").asText()).isEqualTo("ERROR");
        assertThat(body.get("error_code").asText()).isEqualTo("ai_evidence_insufficient");
        assertThat(body.get("policy_analysis").get("policy_assessments").get(0).get("state").asText()).isEqualTo("INSUFFICIENT");
        assertThat(body.get("findings").toString()).doesNotContain("MISSING_POLICY").contains("HUMAN_REVIEW");
    }

    @Test
    void forgedPolicyQuoteFailsClosed() throws Exception {
        long validation = accepted();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> result = result(call.getArgument(0), List.of(), List.of());
            result.put("policy_assessments", List.of()); result.put("policy_assessments_truncated", false);
            result.put("no_policy_requirements_reason", "정책 없음");
            result.put("no_policy_requirements_evidence", Map.of("field", "goal", "quote", "없는 내용"));
            return response(validation, result);
        });
        assertThat(worker.runOnce()).isTrue();
        var body = JSON.readTree(api.get("/api/projects/" + project + "/validations/" + validation, owner).body());
        assertThat(body.get("status").asText()).isEqualTo("ERROR");
        assertThat(body.get("error_code").asText()).isEqualTo("ai_invalid_output");
        assertThat(body.has("policy_analysis")).isFalse();
    }

    @Test
    void searchFailureDoesNotBecomePolicyAbsence() {
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenThrow(new IllegalStateException("synthetic search unavailable"));
        long validation = accepted();
        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(analyzer);
        var body = JSON.readTree(api.get("/api/projects/" + project + "/validations/" + validation, owner).body());
        assertThat(body.get("status").asText()).isEqualTo("ERROR");
        assertThat(body.get("findings").toString()).doesNotContain("MISSING_POLICY").contains("RETRY");
    }

    @Test
    void conflictingSearchCandidatesPersistBothSidesWithoutCreatingPlanRefs() throws Exception {
        long first = policy("POL-186-A", "로그인 5회 실패 시 30분 잠금");
        long second = policy("POL-186-B", "로그인 3회 실패 시 10분 잠금");
        var hit1 = new HybridDocumentCandidateSearch.Candidate(Map.of("type", "DESIGN_ITEM", "id", Long.toString(first)), Set.of("KEYWORD"));
        var hit2 = new HybridDocumentCandidateSearch.Candidate(Map.of("type", "DESIGN_ITEM", "id", Long.toString(second)), Set.of("KEYWORD"));
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(hit1, hit2), false, false));
        long validation = accepted();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            var items = maps(request.get("approved_items"));
            var a = items.get(0); var b = items.get(1);
            String va = (String) a.get("id"), vb = (String) b.get("id");
            var refs = List.of(Map.of("design_item_version_id", va, "path", List.of("description"), "quote", "5회"),
                    Map.of("design_item_version_id", vb, "path", List.of("description"), "quote", "3회"));
            var conflict = Map.of("design_item_id", b.get("design_item_id"), "design_item_version_id", vb,
                    "evidence_ids", List.of(), "content_paths", List.of(List.of("description")));
            Map<String, Object> finding = new LinkedHashMap<>(Map.of("check_key", "policy-gap-v1", "kind", "CONFLICT", "severity", "BLOCKING",
                    "message", "잠금 정책 충돌", "suggested_action", "정책 검토", "design_item_id", a.get("design_item_id"),
                    "design_item_version_id", va, "sources", List.of(), "evidence_ids", List.of(), "policy_need_id", "lock"));
            finding.put("conflicting_design_refs", List.of(conflict));
            Map<String, Object> result = result(request, List.of(finding), List.of());
            result.put("policy_assessments", List.of(Map.of("need_id", "lock", "topic", "잠금", "state", "CONTRADICTORY",
                    "plan_evidence", Map.of("field", "goal", "quote", "목표"), "design_item_version_ids", List.of(va, vb),
                    "approved_content_refs", refs, "evidence_ids", List.of(), "explanation", "잠금 조건이 다릅니다")));
            result.put("policy_assessments_truncated", false);
            return response(validation, result);
        });
        assertThat(worker.runOnce()).isTrue();
        var body = JSON.readTree(api.get("/api/projects/" + project + "/validations/" + validation, owner).body());
        assertThat(body.get("status").asText()).as(body.toString()).isEqualTo("NEEDS_REVIEW");
        assertThat(body.get("findings").get(0).get("policy_need_id").asText()).isEqualTo("lock");
        assertThat(body.get("findings").get(0).get("conflicting_design_refs").get(0).get("design_item_id").asText())
                .isEqualTo(Long.toString(second));
        assertThat(api.jdbc.sql("select count(*) from validation_design_ref where validation_id=:v")
                .param("v", validation).query(Integer.class).single()).isZero();
    }

    private long accepted() {
        return accepted("[]");
    }

    private long accepted(String refs) {
        var task = api.post("/api/projects/" + project + "/tasks", api.planJson("정책 검사", refs, "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t").param("t", taskId).query(Long.class).single();
        var accepted = api.post("/api/projects/" + project + "/tasks/" + taskId + "/validations",
                "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":" + TaskApiSupport.longField(task.body(), "lock_version") + "}", owner);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        return TaskApiSupport.longField(accepted.body(), "id");
    }

    private long policy(String key, String description) {
        long item = api.jdbc.sql("insert into design_item(project_id,key,kind) values(:p,:k,'POLICY') returning id")
                .param("p", project).param("k", key).query(Long.class).single();
        long version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values(:i,1,'APPROVED',:title,1,cast(:content as jsonb),:hash,'HUMAN',now()) returning id
                """).param("i", item).param("title", key).param("content", JSON.writeValueAsString(Map.of("description", description)))
                .param("hash", "hash-" + key).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values(:b,:p,:i,:v)")
                .param("b", baseline).param("p", project).param("i", item).param("v", version).update();
        return item;
    }

    private static Map<String, Object> result(Map<String, Object> request, List<Map<String, Object>> findings,
                                               List<Map<String, Object>> unassessed) {
        Set<Object> skipped = unassessed.stream().map(u -> u.get("check_key")).collect(java.util.stream.Collectors.toSet());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis_status", unassessed.isEmpty() ? "COMPLETE" : "PARTIAL");
        result.put("checked_keys", checks(request).stream().map(c -> c.get("key")).filter(k -> !skipped.contains(k)).toList());
        result.put("unassessed", unassessed); result.put("findings", findings);
        return result;
    }
    private static Map<String, Object> response(long validation, Map<String, Object> result) {
        return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 10)), "result", result);
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return (List<Map<String, Object>>) value; }
    private static List<Map<String, Object>> checks(Map<String, Object> request) { return maps(request.get("checks")); }
}
