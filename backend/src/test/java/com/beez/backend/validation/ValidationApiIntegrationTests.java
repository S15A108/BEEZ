package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.mcp.dto.ValidateTaskPlanArgs;
import com.beez.backend.mcp.service.ValidationMcpService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비동기 검증 접수·실행·결과 조회 (S15P31A108-159): VAL-A01~A03, MCP-11, 워커.
 * AI 서버에는 아직 /v1/validate 가 없어 분석 호출({@link ValidationAnalyzer})을 가짜로 바꿔 끼운다.
 * 워커는 주기 실행을 끄고(application-test) runOnce 를 직접 부른다.
 */
class ValidationApiIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    ValidationWorker worker;

    @Autowired
    ValidationMcpService mcp;

    @MockitoBean
    ValidationAnalyzer analyzer;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    Long outsider;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");
    }

    // ---- 도우미 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 계획 JSON. unlinkedJson 이 null 이면 [] */
    private String planJson(String title, String refsJson, boolean noContractChange, String unlinkedJson) {
        String unlinked = "[]";
        if (unlinkedJson != null) {
            unlinked = unlinkedJson;
        }
        return api.planJson(title, refsJson, "[]")
                .replace("\"unlinked\": []", "\"unlinked\": " + unlinked)
                .replace("\"no_contract_change\": true", "\"no_contract_change\": " + noContractChange);
    }

    /** 작업 생성 → {taskId, revisionId, lockVersion} */
    private long[] createTask(String plan, Long account) throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), plan, account);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        return new long[] {taskId, revisionId, TaskApiSupport.longField(res.body(), "lock_version")};
    }

    private HttpResponse<String> accept(long[] t, Long account) throws Exception {
        return api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", account);
    }

    private JsonNode body(HttpResponse<String> res, int status) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return JSON.readTree(res.body());
    }

    private String column(String table, String column, long id) {
        return api.jdbc.sql("select " + column + "::text from " + table + " where id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private Integer count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    private static List<String> kinds(JsonNode findings) {
        List<String> out = new ArrayList<>();
        for (JsonNode f : findings) {
            out.add(f.get("kind").asText());
        }
        return out;
    }

    /** AI 성공 응답 (COMPLETE 또는 PARTIAL). checks 는 요청에서 그대로 받아 checked_keys 로 쓴다 */
    private static Map<String, Object> aiResponse(Map<String, Object> request, List<Map<String, Object>> findings,
                                                  List<Map<String, Object>> unassessed) {
        List<String> checked = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> u : unassessed) {
            skipped.add(String.valueOf(u.get("check_key")));
        }
        for (Object c : (List<?>) request.get("checks")) {
            String key = String.valueOf(((Map<?, ?>) c).get("key"));
            if (!skipped.contains(key)) {
                checked.add(key);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        String status = "COMPLETE";
        if (!unassessed.isEmpty()) {
            status = "PARTIAL";
        }
        result.put("analysis_status", status);
        result.put("checked_keys", checked);
        result.put("unassessed", unassessed);
        result.put("findings", findings);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", "test-model");
        response.put("usage", List.of(Map.of("request_key", "llm-validate-x-attempt-1-call-1", "input_tokens", 10, "output_tokens", 5)));
        return response;
    }

    private static Map<String, Object> candidate(String checkKey, String kind, String severity, String itemId, String versionId) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("check_key", checkKey);
        f.put("kind", kind);
        f.put("severity", severity);
        f.put("message", kind + " 후보");
        if (itemId != null) {
            f.put("design_item_id", itemId);
            f.put("design_item_version_id", versionId);
        }
        f.put("sources", List.of());
        f.put("evidence_ids", List.of());
        f.put("suggested_action", "계획을 보완하세요");
        return f;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(Map<String, Object> response) {
        return (Map<String, Object>) response.get("result");
    }

    // ---- 규칙만으로 끝나는 경우 (200) ----

    @Test
    void rules_declaredNoChangeButChangesRef_isNeedsInput() throws Exception {
        long[] t = createTask(planJson("A", api.changesRef("API-01"), true, null), member);
        JsonNode v = body(accept(t, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(kinds(v.get("findings"))).containsExactly("MISSING_INPUT");
        assertThat(v.get("findings").get(0).get("source").asText()).isEqualTo("RULE");
        assertThat(v.get("findings").get(0).get("next_action").asText()).isNotBlank();
        assertThat(v.has("error_code")).isFalse();
        assertThat(v.get("finished_at").isNull()).isFalse();
        assertThat(count("select count(*) from validation_job")).isZero();
    }

    @Test
    void rules_changeDeclaredButNothingToChange_isNeedsInput() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), false, null), member);
        JsonNode v = body(accept(t, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(kinds(v.get("findings"))).containsExactly("MISSING_INPUT");
    }

    @Test
    void rules_staleReference_isNeedsInput() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, null), member);
        // 새 기준 발행: API-01 이 v2 가 되어 계획이 본 v1 은 현재 기준에 없다
        api.publish(2, Map.of("API-01", api.newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));
        JsonNode v = body(accept(t, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(kinds(v.get("findings"))).containsExactly("STALE_REF");
        assertThat(v.get("findings").get(0).get("design_item_id").asLong()).isEqualTo(api.designItemId("API-01"));
    }

    @Test
    void rules_changesOccupiedByRunningTask_isNeedsReview() throws Exception {
        // 작업 A 가 API-01 을 CHANGES 로 점유하고 시작한 상태
        long[] a = createTask(planJson("A", api.changesRef("API-01"), false, null), member);
        long passA = api.passValidation(a[0]);
        HttpResponse<String> start = api.post(tasksPath() + "/" + a[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + a[1] + "\",\"expected_lock_version\":" + a[2]
                        + ",\"validation_id\":\"" + passA + "\"}", member);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(200);
        // 작업 B 도 같은 항목을 CHANGES
        long[] b = createTask(planJson("B", api.changesRef("API-01"), false, null), member);
        JsonNode v = body(accept(b, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(kinds(v.get("findings"))).containsExactly("CONFLICT");
        assertThat(v.get("findings").get(0).get("related_task_id").asLong()).isEqualTo(a[0]);
        assertThat(v.get("findings").get(0).get("resolution").asText()).isEqualTo("HUMAN_REVIEW");
        assertThat(v.get("related_task_refs").get(0).get("task_id").asLong()).isEqualTo(a[0]);
        assertThat(v.get("related_task_refs").get(0).get("plan_revision_id").asLong()).isEqualTo(a[1]);
    }

    @Test
    void rules_readsOnly_isPass_andStartGateAcceptsIt() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, null), member);
        JsonNode v = body(accept(t, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("PASS");
        assertThat(v.get("trigger").asText()).isEqualTo("AGENT");
        assertThat(v.has("stale_reason")).isFalse();
        assertThat(v.get("current_attempt").asInt()).isZero();
        assertThat(v.get("design_refs")).hasSize(1);
        assertThat(v.get("design_refs").get(0).get("relation").asText()).isEqualTo("READS");
        assertThat(v.get("baseline_id").asLong()).isEqualTo(
                api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single());
        // 끝까지: 이 검증으로 START 가 관문을 통과한다
        HttpResponse<String> start = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2]
                        + ",\"validation_id\":\"" + v.get("id").asText() + "\"}", member);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(start.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    // ---- 의미 분석이 필요한 경우 (202 → 워커) ----

    @Test
    void accept_implementsOnly_isQueuedForDesignAlignment() throws Exception {
        // 미연결 내용·관련 작업이 없어도 CHANGES·IMPLEMENTS 는 승인 설계와 대조한다 (버전만 맞는다고 PASS 하지 않는다)
        long[] t = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        JsonNode v = body(accept(t, member), 202);
        assertThat(v.get("status").asText()).isEqualTo("RUNNING");
        assertThat(column("validation", "summary", v.get("id").asLong()))
                .contains("\"design-" + api.designItemId("API-01") + "\"").contains("DESIGN_ALIGNMENT")
                .doesNotContain("plan-completeness");
    }

    @Test
    void accept_withUnlinked_queuesSemanticAnalysis() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"토큰 만료 정책은 아직 없음\"}]"), member);
        JsonNode v = body(accept(t, member), 202);
        long id = v.get("id").asLong();
        assertThat(v.get("status").asText()).isEqualTo("RUNNING");
        assertThat(v.get("current_attempt").asInt()).isEqualTo(1);
        assertThat(v.get("findings")).isEmpty();
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("QUEUED");
        assertThat(count("select count(*) from validation_run where validation_id = " + id + " and attempt = 1 and status = 'RUNNING'"))
                .isEqualTo(1);
        assertThat(column("validation", "summary", id)).contains("plan-completeness");
        // 조회는 새 실행을 만들지 않는다 (VAL-A02)
        body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(count("select count(*) from validation_run")).isEqualTo(1);
    }

    private long id(long validationId, String table) {
        return api.jdbc.sql("select id from " + table + " where validation_id = :v").param("v", validationId)
                .query(Long.class).single();
    }

    @Test
    void worker_complete_withoutCandidates_isPass() throws Exception {
        long[] t = createTask(planJson("A", api.implementsRef("API-01"), true, "[{\"text\":\"응답 캐시 기준 미정\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            sent.set(req);
            return aiResponse(req, List.of(), List.of());
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(worker.runOnce()).isFalse();   // 큐가 비었다

        // 요청은 고정한 입력으로 만들어진다
        Map<String, Object> req = sent.get();
        assertThat(req.get("validation_id")).isEqualTo(String.valueOf(id));
        assertThat(req.get("attempt")).isEqualTo(1);
        List<String> checkKeys = new ArrayList<>();
        for (Object c : (List<?>) req.get("checks")) {
            checkKeys.add(String.valueOf(((Map<?, ?>) c).get("key")));
        }
        assertThat(checkKeys).containsExactly("plan-completeness", "design-" + api.designItemId("API-01"));
        assertThat((List<?>) req.get("approved_items")).hasSize(1);
        assertThat(((Map<?, ?>) ((Map<?, ?>) req.get("plan_revision")).get("plan")).get("unlinked")).isNotNull();
        assertThat(req.get("evidence")).isEqualTo(List.of());

        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("PASS");
        assertThat(v.get("finished_at").isNull()).isFalse();
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("OK");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("DONE");
        assertThat(column("validation", "summary", id)).contains("\"analysis_status\": \"COMPLETE\"").contains("test-model");
    }

    @Test
    void worker_complete_withCandidates_judgesByRules() throws Exception {
        long item = api.designItemId("API-01");
        long version = api.currentVersionId("API-01");
        // ① MISSING_INPUT 만 → NEEDS_INPUT
        long[] t1 = createTask(planJson("A", api.implementsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long v1 = body(accept(t1, member), 202).get("id").asLong();
        when(analyzer.analyze(any())).thenAnswer(inv -> aiResponse(inv.getArgument(0),
                List.of(candidate("plan-completeness", "MISSING_INPUT", "BLOCKING", null, null)), List.of()));
        worker.runOnce();
        JsonNode r1 = body(api.get("/api/projects/" + projectId + "/validations/" + v1, member), 200);
        assertThat(r1.get("status").asText()).isEqualTo("NEEDS_INPUT");
        assertThat(r1.get("findings").get(0).get("source").asText()).isEqualTo("MODEL");
        assertThat(r1.get("findings").get(0).get("resolution").asText()).isEqualTo("FIX_PLAN");
        assertThat(column("validation_finding", "check_key", r1.get("findings").get(0).get("id").asLong())).isEqualTo("plan-completeness");

        // ② MISSING_INPUT + MISSING_POLICY → 검토 필요 우선
        long[] t2 = createTask(planJson("B", api.implementsRef("API-02"), true, "[{\"text\":\"y\"}]"), member);
        long v2 = body(accept(t2, member), 202).get("id").asLong();
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of(
                candidate("plan-completeness", "MISSING_INPUT", "BLOCKING", null, null),
                candidate("design-" + api.designItemId("API-02"), "MISSING_POLICY", "BLOCKING",
                        String.valueOf(api.designItemId("API-02")), String.valueOf(api.currentVersionId("API-02")))), List.of())).when(analyzer).analyze(any());
        worker.runOnce();
        JsonNode r2 = body(api.get("/api/projects/" + projectId + "/validations/" + v2, member), 200);
        assertThat(r2.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(kinds(r2.get("findings"))).containsExactlyInAnyOrder("MISSING_INPUT", "MISSING_POLICY");

        // ③ INFO 불일치만 → PASS (BLOCKING 이 아니면 막지 않는다)
        long[] t3 = createTask(planJson("C", api.implementsRef("API-01"), true, "[{\"text\":\"z\"}]"), member);
        long v3 = body(accept(t3, member), 202).get("id").asLong();
        doAnswer(inv -> aiResponse(inv.getArgument(0),
                List.of(candidate("design-" + item, "MISMATCH", "INFO", String.valueOf(item), String.valueOf(version))), List.of())).when(analyzer).analyze(any());
        worker.runOnce();
        JsonNode r3 = body(api.get("/api/projects/" + projectId + "/validations/" + v3, member), 200);
        assertThat(r3.get("status").asText()).isEqualTo("PASS");
        assertThat(r3.get("findings")).hasSize(1);
        assertThat(r3.get("findings").get(0).get("validation_run_id").asText()).isNotBlank();

        // ④ 항목·버전 짝이 안 맞는 후보 → 출력 계약 위반 ERROR(ai_invalid_output), FK 위반으로 새지 않는다
        long[] t4 = createTask(planJson("D", api.implementsRef("API-01"), true, "[{\"text\":\"w\"}]"), member);
        long v4 = body(accept(t4, member), 202).get("id").asLong();
        doAnswer(inv -> aiResponse(inv.getArgument(0),
                List.of(candidate("design-" + item, "MISMATCH", "BLOCKING", String.valueOf(item),
                        String.valueOf(api.currentVersionId("API-02")))), List.of())).when(analyzer).analyze(any());
        worker.runOnce();
        JsonNode r4 = body(api.get("/api/projects/" + projectId + "/validations/" + v4, member), 200);
        assertThat(r4.get("status").asText()).isEqualTo("ERROR");
        assertThat(r4.get("error_code").asText()).isEqualTo("ai_invalid_output");
    }

    @Test
    void worker_partial_isErrorForHumanReview() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        when(analyzer.analyze(any())).thenAnswer(inv -> aiResponse(inv.getArgument(0), List.of(), List.of(
                Map.of("check_key", "plan-completeness", "reason", "EVIDENCE_INSUFFICIENT", "message", "근거 부족"))));
        worker.runOnce();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("ai_evidence_insufficient");
        JsonNode f = v.get("findings").get(0);
        assertThat(f.get("source").asText()).isEqualTo("MODEL");
        assertThat(f.get("kind").asText()).isEqualTo("ERROR");
        assertThat(f.get("severity").asText()).isEqualTo("BLOCKING");
        assertThat(f.get("resolution").asText()).isEqualTo("HUMAN_REVIEW");
        // 모델은 돌았으니 사용량·검사 키·미검사 사유를 진단용으로 남긴다 (비용 기록에서 빠지지 않게)
        assertThat(column("validation", "summary", id)).contains("llm-validate-x-attempt-1-call-1")
                .contains("\"analysis_status\": \"PARTIAL\"").contains("EVIDENCE_INSUFFICIENT").contains("partial_findings");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("ERROR");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("FAILED");
        assertThat(column("validation_job", "lease_token", id(id, "validation_job"))).isNull();
    }

    @Test
    void worker_contractViolation_isErrorRetry() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        // 검사 키를 하나 빠뜨린 응답
        when(analyzer.analyze(any())).thenAnswer(inv -> {
            Map<String, Object> res = aiResponse(inv.getArgument(0), List.of(), List.of());
            ((Map<String, Object>) res.get("result")).put("checked_keys", List.of());
            return res;
        });
        worker.runOnce();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("ai_invalid_output");
        assertThat(v.get("findings").get(0).get("resolution").asText()).isEqualTo("RETRY");
        assertThat(column("validation", "summary", id)).contains("llm-validate-x-attempt-1-call-1");
    }

    @Test
    void worker_malformedResponses_areInvalidOutput_notPass() throws Exception {
        long item = api.designItemId("API-01");
        long version = api.currentVersionId("API-01");
        String design = "design-" + item;
        List<Function<Map<String, Object>, Map<String, Object>>> broken = new ArrayList<>();
        // ① findings 칸이 없음 — 빈 목록으로 보면 PASS 가 된다
        broken.add(req -> {
            Map<String, Object> r = aiResponse(req, List.of(), List.of());
            result(r).remove("findings");
            return r;
        });
        // ② findings 원소가 객체가 아님
        broken.add(req -> {
            Map<String, Object> r = aiResponse(req, List.of(), List.of());
            result(r).put("findings", List.of("invalid finding"));
            return r;
        });
        // ③ 200 인데 본문이 비어 있음
        broken.add(req -> null);
        // ④ 필수 정보 누락인데 BLOCKING 이 아님
        broken.add(req -> aiResponse(req, List.of(candidate(design, "MISSING_INPUT", "WARNING", null, null)), List.of()));
        // ⑤ 필수 정책 누락인데 BLOCKING 이 아님
        broken.add(req -> aiResponse(req, List.of(candidate(design, "MISSING_POLICY", "INFO", null, null)), List.of()));
        // ⑥ 요청에 넣지 않은 출처
        broken.add(req -> {
            Map<String, Object> f = candidate(design, "MISMATCH", "BLOCKING", String.valueOf(item), String.valueOf(version));
            f.put("sources", List.of(Map.of("document_id", "999", "document_version_id", "999", "version_no", 1, "location", "어딘가")));
            return aiResponse(req, List.of(f), List.of());
        });
        // ⑦ 요청에 없는 근거 키
        broken.add(req -> {
            Map<String, Object> f = candidate(design, "MISMATCH", "BLOCKING", String.valueOf(item), String.valueOf(version));
            f.put("evidence_ids", List.of("evidence-x"));
            return aiResponse(req, List.of(f), List.of());
        });
        // ⑧ unassessed 가 배열이 아님
        broken.add(req -> {
            Map<String, Object> r = aiResponse(req, List.of(), List.of());
            result(r).put("unassessed", "none");
            return r;
        });
        // ⑨ 후속 행동 제안이 없음
        broken.add(req -> {
            Map<String, Object> f = candidate(design, "MISMATCH", "INFO", String.valueOf(item), String.valueOf(version));
            f.remove("suggested_action");
            return aiResponse(req, List.of(f), List.of());
        });

        for (int i = 0; i < broken.size(); i++) {
            Function<Map<String, Object>, Map<String, Object>> make = broken.get(i);
            long[] t = createTask(planJson("B" + i, api.implementsRef("API-01"), true, null), member);
            long id = body(accept(t, member), 202).get("id").asLong();
            doAnswer(inv -> make.apply(inv.getArgument(0))).when(analyzer).analyze(any());
            worker.runOnce();
            JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
            assertThat(v.get("status").asText()).as("경우 " + (i + 1)).isEqualTo("ERROR");
            assertThat(v.get("error_code").asText()).as("경우 " + (i + 1)).isEqualTo("ai_invalid_output");
            assertThat(column("validation_run", "status", id(id, "validation_run"))).as("경우 " + (i + 1)).isEqualTo("ERROR");
        }
    }

    @Test
    void worker_aiStaleRef_isRecheckedAgainstCurrentBaseline() throws Exception {
        long item = api.designItemId("API-01");
        long version = api.currentVersionId("API-01");
        Map<String, Object> stale = candidate("design-" + item, "STALE_REF", "BLOCKING", String.valueOf(item), String.valueOf(version));

        // ① 다시 대조해 보니 아직 최신 → 추론만으로 낡았다고 확정하지 않고 사람 검토
        long[] t1 = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        long v1 = body(accept(t1, member), 202).get("id").asLong();
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of(stale), List.of())).when(analyzer).analyze(any());
        worker.runOnce();
        JsonNode r1 = body(api.get("/api/projects/" + projectId + "/validations/" + v1, member), 200);
        assertThat(r1.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(r1.get("findings")).hasSize(1);
        assertThat(r1.get("findings").get(0).get("resolution").asText()).isEqualTo("HUMAN_REVIEW");

        // ② AI 가 도는 사이 기준이 바뀌어 정말 낡음 → 규칙 판정과 같이 보완 필요(NEEDS_INPUT)
        long[] t2 = createTask(planJson("B", api.implementsRef("API-01"), true, null), member);
        long v2 = body(accept(t2, member), 202).get("id").asLong();
        doAnswer(inv -> {
            api.publish(2, Map.of("API-01", api.newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));
            return aiResponse(inv.getArgument(0), List.of(stale), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();
        JsonNode r2 = body(api.get("/api/projects/" + projectId + "/validations/" + v2, member), 200);
        assertThat(r2.get("status").asText()).isEqualTo("NEEDS_INPUT");
        List<String> sources = new ArrayList<>();
        for (JsonNode f : r2.get("findings")) {
            sources.add(f.get("source").asText() + ":" + f.get("kind").asText() + ":" + f.get("resolution").asText());
        }
        assertThat(sources).containsExactlyInAnyOrder("RULE:STALE_REF:FIX_PLAN", "MODEL:STALE_REF:FIX_PLAN");
    }

    @Test
    void worker_timeout_isErrorRetry_withTimeoutRun() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        when(analyzer.analyze(any())).thenThrow(new ValidationAnalyzer.AnalyzerException("ai_timeout", "시간 초과", null));
        worker.runOnce();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("ai_timeout");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("TIMEOUT");
    }

    @Test
    void worker_lateResult_isDiscarded_whenValidationWentStale() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        // AI 가 도는 동안 검증이 무효화된다 (설계 발행·계획 변경·인계가 하는 일을 SQL 로)
        when(analyzer.analyze(any())).thenAnswer(inv -> {
            api.jdbc.sql("update validation set status = 'STALE', stale_reason = 'PLAN_CHANGED' where id = :v").param("v", id).update();
            api.jdbc.sql("update validation_job set status = 'CANCELLED', lease_token = null, lease_until = null where validation_id = :v")
                    .param("v", id).update();
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        });
        worker.runOnce();
        assertThat(column("validation", "status", id)).isEqualTo("STALE");                 // 늦은 결과가 상태를 바꾸지 않는다
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isZero();
    }

    // ---- 접수 오류 ----

    @Test
    void accept_rejectsWrongCallerAndStaleInput() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, null), member);
        // 담당자 아님 (팀장도) 403
        assertThat(accept(t, leader).statusCode()).isEqualTo(403);
        // 비멤버 404, 비로그인 401
        assertThat(accept(t, outsider).statusCode()).isEqualTo(404);
        assertThat(accept(t, null).statusCode()).isEqualTo(401);
        // 잠금 번호가 다름 409
        HttpResponse<String> stale = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + (t[2] + 7) + "}", member);
        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.body()).contains("revision_conflict");
        // 지난 리비전 409
        HttpResponse<String> old = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + (t[1] + 999) + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(old.statusCode()).isEqualTo(409);
        // 형식 오류 400
        assertThat(api.post(tasksPath() + "/" + t[0] + "/validations", "{\"plan_revision_id\":\"abc\",\"expected_lock_version\":0}", member)
                .statusCode()).isEqualTo(400);
        assertThat(count("select count(*) from validation")).isZero();
        // 취소된 작업 400 invalid_transition
        long[] closed = createTask(planJson("B", api.readsRef("API-01"), true, null), member);
        api.jdbc.sql("update task set status = 'CANCELLED', cancelled_at = now() where id = :t").param("t", closed[0]).update();
        HttpResponse<String> cancelled = accept(closed, member);
        assertThat(cancelled.statusCode()).isEqualTo(400);
        assertThat(cancelled.body()).contains("invalid_transition");
        // 기준 없음 409 baseline_missing
        api.jdbc.sql("update project set current_baseline_id = null where id = :p").param("p", projectId).update();
        HttpResponse<String> noBaseline = accept(t, member);
        assertThat(noBaseline.statusCode()).isEqualTo(409);
        assertThat(noBaseline.body()).contains("baseline_missing");
    }

    // ---- 조회 ----

    @Test
    void queries_hideOtherProjects_andPageHistory() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, null), member);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(body(accept(t, member), 200).get("id").asLong());
        }
        String history = tasksPath() + "/" + t[0] + "/validations";
        JsonNode first = body(api.get(history + "?limit=2", member), 200);
        assertThat(first.get("items")).hasSize(2);
        assertThat(first.get("items").get(0).get("id").asLong()).isEqualTo(ids.get(2));   // 최신순
        assertThat(first.get("has_more").asBoolean()).isTrue();
        JsonNode second = body(api.get(history + "?limit=2&cursor=" + first.get("next_cursor").asText(), member), 200);
        assertThat(second.get("items")).hasSize(1);
        assertThat(second.get("has_more").asBoolean()).isFalse();

        assertThat(api.get("/api/projects/" + projectId + "/validations/" + ids.get(0), outsider).statusCode()).isEqualTo(404);
        assertThat(api.get("/api/projects/" + (projectId + 99) + "/validations/" + ids.get(0), member).statusCode()).isEqualTo(404);
        assertThat(api.get(history, outsider).statusCode()).isEqualTo(404);
        assertThat(api.get(tasksPath() + "/999999/validations", member).statusCode()).isEqualTo(404);
    }

    // ---- MCP-11 ----

    @Test
    void mcp_validateTaskPlan_sameKeyReplays_differentInputConflicts() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, null), member);
        CallerContext agent = CallerContext.agent(member, api.agentTokenId());
        ValidateTaskPlanArgs args = new ValidateTaskPlanArgs(String.valueOf(t[0]), String.valueOf(t[1]), (int) t[2], "k-1");
        ValidationResponse first = mcp.validateTaskPlan(agent, projectId, args);
        assertThat(first.status()).isEqualTo("PASS");
        ValidationResponse again = mcp.validateTaskPlan(agent, projectId, args);
        assertThat(again).isEqualTo(first);
        assertThat(count("select count(*) from validation")).isEqualTo(1);
        // 같은 키·다른 입력 → 409
        ValidateTaskPlanArgs other = new ValidateTaskPlanArgs(String.valueOf(t[0]), String.valueOf(t[1]), (int) t[2] + 1, "k-1");
        assertThatThrownBy(() -> mcp.validateTaskPlan(agent, projectId, other))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getCode()).isEqualTo("idempotency_conflict");
                });
        // 키 없음 400, 팀장 토큰(담당자 아님) 403, 사람 세션은 MCP 서비스가 아니라 REST 로 — 여기서는 담당자만 확인
        assertThatThrownBy(() -> mcp.validateTaskPlan(agent, projectId,
                new ValidateTaskPlanArgs(String.valueOf(t[0]), String.valueOf(t[1]), (int) t[2], null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> mcp.validateTaskPlan(CallerContext.agent(leader, api.leaderAgentTokenId()), projectId,
                new ValidateTaskPlanArgs(String.valueOf(t[0]), String.valueOf(t[1]), (int) t[2], "k-2")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
