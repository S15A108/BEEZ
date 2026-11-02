package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.mcp.dto.ValidateTaskPlanArgs;
import com.beez.backend.mcp.service.ValidationMcpService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.task.service.StartGate;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.service.AnalysisResultApplier;
import com.beez.backend.validation.service.FakeValidationAnalyzer;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.net.http.HttpResponse;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    /** 실제 저장소를 그대로 쓰되, 경합 순서를 고정하는 시험에서 관련 작업 조회 직후 멈출 수 있게 감싼다 */
    @MockitoSpyBean
    ValidationRepository repository;

    @Autowired
    AnalysisResultApplier applier;

    @Autowired
    StartGate startGate;

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

    /** START 요청 (검증 번호를 실어 보낸다) */
    private HttpResponse<String> start(long[] t, long validationId, Long account) throws Exception {
        return api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2]
                        + ",\"validation_id\":\"" + validationId + "\"}", account);
    }

    @Test
    void fakeAnalyzerPass_isRejectedByStartGate_whenFakeAnalyzerIsOff() throws Exception {
        // 가짜 분석기로 PASS 를 만든다 (실행 기록의 분석기 버전 = fake-analyzer)
        long[] t = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        when(analyzer.analyze(any())).thenAnswer(inv -> new FakeValidationAnalyzer().analyze(inv.getArgument(0)));
        assertThat(worker.runOnce()).isTrue();
        assertThat(column("validation", "status", id)).isEqualTo("PASS");
        assertThat(column("validation_run", "analyzer_version", id(id, "validation_run"))).isEqualTo(FakeValidationAnalyzer.MODEL);

        // 가짜 분석기를 끈 서버(테스트 기본값): 그 PASS 로는 시작할 수 없다
        HttpResponse<String> rejected = start(t, id, member);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
        assertThat(rejected.body()).contains("\"code\":\"validation_not_passed\"").contains("가짜 분석기");
        assertThat(api.jdbc.sql("select status from task where id = :t").param("t", t[0]).query(String.class).single())
                .isNotEqualTo("IN_PROGRESS");

        // 가짜 분석기를 켠 서버(개발 서버): 같은 PASS 로 시작할 수 있다
        ReflectionTestUtils.setField(startGate, "fakeAnalyzerEnabled", true);
        try {
            HttpResponse<String> started = start(t, id, member);
            assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
            assertThat(TaskApiSupport.stringField(started.body(), "status")).isEqualTo("IN_PROGRESS");
        } finally {
            ReflectionTestUtils.setField(startGate, "fakeAnalyzerEnabled", false);
        }
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
        // AI 가 모델을 부른 뒤 504 로 실패하면서 오류 본문에 사용량을 넣어 준 경우
        List<Map<String, Object>> usage = List.of(Map.of("request_key", "llm-validate-x-attempt-1-call-1", "input_tokens", 7,
                "output_tokens", 0, "tokens_known", true));
        when(analyzer.analyze(any())).thenThrow(new ValidationAnalyzer.AnalyzerException("ai_timeout", "시간 초과", null, usage));
        worker.runOnce();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("ai_timeout");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("TIMEOUT");
        // 실패한 호출의 사용량도 실행에 남는다
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).contains("llm-validate-x-attempt-1-call-1");
        assertThat(column("validation", "summary", id)).contains("llm-validate-x-attempt-1-call-1");
    }

    @Test
    void worker_applyCrash_isClosedAsExecutionFailure_withUsageKept() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        when(analyzer.analyze(any())).thenAnswer(inv -> aiResponse(inv.getArgument(0), List.of(), List.of()));
        // 정상 결과를 저장하는 순간 DB 오류 — 첫 번째 저장만 실패하고 실패 기록은 들어간다
        AtomicBoolean crashed = new AtomicBoolean(false);
        doAnswer(inv -> {
            if (crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("저장 실패 (시험용)");
            }
            return inv.callRealMethod();
        }).when(repository).finish(anyLong(), any(), any());

        worker.runOnce();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("validation_execution_failed");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("ERROR");
        // 모델은 불렀으니 사용량은 남는다
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).contains("llm-validate-x-attempt-1-call-1");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isEqualTo(1);   // 롤백돼 ERROR 하나만
    }

    @Test
    void worker_invalidUsageShape_isInvalidOutput_notExecutionFailure() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        // usage 가 배열이 아니라 객체로 온다 — 계약 위반이지만, 그 값을 그대로 저장하려다 BE 오류로 바뀌면 안 된다
        when(analyzer.analyze(any())).thenAnswer(inv -> {
            Map<String, Object> res = aiResponse(inv.getArgument(0), List.of(), List.of());
            res.put("usage", Map.of("input_tokens", 10));
            return res;
        });
        worker.runOnce();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("ai_invalid_output");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("ERROR");
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).isNull();      // 모양이 틀린 사용량은 저장하지 않는다
        assertThat(column("validation", "summary", id)).contains("usage_rejected");            // 원문은 진단용으로만
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

    @Test
    void worker_resultAfterPlanChanged_isDiscarded_andValidationStale() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        doAnswer(inv -> {
            // AI 가 도는 동안 담당자가 계획 리비전을 올린다 (TSK-A04)
            String plan = api.planJson("A2", api.readsRef("API-01"), "[]").trim();
            String revision = plan.substring(0, plan.lastIndexOf('}'))
                    + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + t[2] + "\n}";
            HttpResponse<String> res = api.post(tasksPath() + "/" + t[0] + "/plan-revisions", revision, member);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();
        // 지난 계획의 결과는 PASS 로 남기지 않는다
        assertThat(column("validation", "status", id)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", id)).isEqualTo("PLAN_CHANGED");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isZero();
    }

    @Test
    void relatedTaskPlanChangedDuringAnalysis_discardsResult_andStartIsRefused() throws Exception {
        // A 와 B 가 같은 설계 항목을 본다. A 의 검증에는 B 의 지금 계획이 관련 계획으로 들어간다
        long[] b = createTask(planJson("B", api.implementsRef("API-01"), true, null), member);
        long[] a = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        long id = body(accept(a, member), 202).get("id").asLong();
        assertThat(api.jdbc.sql("select related_plan_revision_id from validation_task_ref where validation_id = :v and related_task_id = :t")
                .param("v", id).param("t", b[0]).query(Long.class).single()).isEqualTo(b[1]);
        doAnswer(inv -> {
            // AI 가 도는 동안 B 의 계획이 바뀐다
            String plan = api.planJson("B2", api.implementsRef("API-01"), "[]").trim();
            String revision = plan.substring(0, plan.lastIndexOf('}'))
                    + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + b[2] + "\n}";
            HttpResponse<String> res = api.post(tasksPath() + "/" + b[0] + "/plan-revisions", revision, member);
            assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();

        // B 의 지난 계획으로 낸 결과는 PASS 로 남기지 않는다
        assertThat(column("validation", "status", id)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", id)).isEqualTo("RELATED_PLAN_CHANGED");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isZero();
        // 판정은 버려도 실제로 모델을 부른 사용량·모델은 남긴다 (비용 집계)
        assertThat(column("validation_run", "analyzer_version", id(id, "validation_run"))).isEqualTo("test-model");
        assertThat(api.jdbc.sql("select summary->>'model' from validation where id = :v").param("v", id).query(String.class).single())
                .isEqualTo("test-model");
        assertThat(api.jdbc.sql("select summary->'usage'->0->>'input_tokens' from validation where id = :v").param("v", id)
                .query(String.class).single()).isEqualTo("10");
        assertThat(column("validation", "summary", id)).doesNotContain("partial_findings");
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).contains("llm-validate-x-attempt-1-call-1");
        // 그 검증으로는 A 를 시작할 수 없다
        HttpResponse<String> start = api.post(tasksPath() + "/" + a[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + a[1] + "\",\"expected_lock_version\":" + a[2]
                        + ",\"validation_id\":\"" + id + "\"}", member);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(409);
    }

    @Test
    void relatedTaskStartedDuringAnalysis_isNeedsReview_byRulesReevaluatedAtApply() throws Exception {
        // B 는 같은 항목을 CHANGES 하지만 아직 READY 라 접수 때는 점유 충돌이 아니다 → 의미 분석으로 넘어간다
        long[] b = createTask(planJson("B", api.changesRef("API-01"), false, null), member);
        long[] a = createTask(planJson("A", api.changesRef("API-01"), false, null), member);
        long id = body(accept(a, member), 202).get("id").asLong();
        doAnswer(inv -> {
            // AI 가 도는 동안 B 가 시작한다 — 계획 리비전은 그대로고 상태만 READY → IN_PROGRESS
            long passB = api.passValidation(b[0]);
            assertThat(start(b, passB, member).statusCode()).isEqualTo(200);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();

        // 관련 작업의 계획은 같아도 지금 상태로 규칙을 다시 판정해 점유 충돌(NEEDS_REVIEW)로 끝낸다. PASS 로 남기지 않는다
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(v.get("findings")).hasSize(1);
        assertThat(v.get("findings").get(0).get("source").asText()).isEqualTo("RULE");
        assertThat(v.get("findings").get(0).get("kind").asText()).isEqualTo("CONFLICT");
        assertThat(v.get("findings").get(0).get("related_task_id").asLong()).isEqualTo(b[0]);
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("OK");
        assertThat(start(a, id, member).statusCode()).isEqualTo(409);
    }

    @Test
    void relatedTaskStartedWhileQueued_isDecidedByRules_withoutCallingAi() throws Exception {
        long[] b = createTask(planJson("B", api.changesRef("API-01"), false, null), member);
        long[] a = createTask(planJson("A", api.changesRef("API-01"), false, null), member);
        long id = body(accept(a, member), 202).get("id").asLong();
        // 워커가 꺼내기 전에 B 가 시작한다 — 이제 규칙만으로 점유 충돌이 확정된다
        long passB = api.passValidation(b[0]);
        assertThat(start(b, passB, member).statusCode()).isEqualTo(200);
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        doAnswer(inv -> {
            called.set(true);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());

        assertThat(worker.runOnce()).isTrue();
        assertThat(called.get()).isFalse();                 // 버릴 결과를 위해 모델을 부르지 않는다
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(kinds(v.get("findings"))).containsExactly("CONFLICT");
        assertThat(v.get("findings").get(0).get("related_task_id").asLong()).isEqualTo(b[0]);
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).isNull();
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("DONE");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void resultApply_holdsProjectLock_soRelatedPlanChangeCannotSlipInBetweenRecheckAndSave() throws Exception {
        // A 의 검증에 B 의 지금 계획이 관련 계획으로 들어간다
        long[] b = createTask(planJson("B", api.implementsRef("API-01"), true, null), member);
        long[] a = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        long id = body(accept(a, member), 202).get("id").asLong();

        // 결과 반영 트랜잭션 안에서 관련 작업을 다시 조회한 직후 멈춘다 (AI 호출 뒤 첫 조회만)
        AtomicBoolean pauseNext = new AtomicBoolean(false);
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(inv -> {
            Object rows = inv.callRealMethod();
            if (pauseNext.compareAndSet(true, false)) {
                paused.countDown();
                resume.await(10, TimeUnit.SECONDS);
            }
            return rows;
        }).when(repository).openTasksSharingItems(anyLong(), anyLong(), anyLong());
        doAnswer(inv -> {
            pauseNext.set(true);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());

        CompletableFuture<Boolean> applying = CompletableFuture.supplyAsync(() -> worker.runOnce());
        assertThat(paused.await(10, TimeUnit.SECONDS)).isTrue();

        // 그 사이 B 의 계획을 바꾸려는 요청은 프로젝트 잠금에 막혀 A 의 반영이 끝날 때까지 들어오지 못한다
        String plan = api.planJson("B2", api.implementsRef("API-01"), "[]").trim();
        String revision = plan.substring(0, plan.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + b[2] + "\n}";
        CompletableFuture<HttpResponse<String>> changingB = CompletableFuture.supplyAsync(() -> {
            try {
                return api.post(tasksPath() + "/" + b[0] + "/plan-revisions", revision, member);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // B 의 요청이 DB 잠금에서 기다리는 것을 확인한다 (고정 시간 대기 대신 pg_stat_activity 로 실제로 막힌 상태를 본다)
        boolean blocked = false;
        for (int i = 0; i < 100 && !blocked; i++) {
            Thread.sleep(100);
            blocked = count("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and state = 'active' "
                    + "and query ilike '%from project%for update%'") > 0;
        }
        assertThat(blocked).as("B 의 계획 변경이 프로젝트 잠금에서 기다려야 한다").isTrue();
        assertThat(changingB.isDone()).as("관련 작업의 계획 변경이 결과 반영 중간에 끼어들면 안 된다").isFalse();
        assertThat(column("validation", "status", id)).isEqualTo("RUNNING");

        resume.countDown();
        assertThat(applying.get(10, TimeUnit.SECONDS)).isTrue();
        HttpResponse<String> changed = changingB.get(10, TimeUnit.SECONDS);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);

        // 반영이 먼저 끝났으므로 A 는 접수 때 고정한 B 의 계획 그대로 PASS 고, B 의 변경은 그 뒤에 들어갔다
        // (반대로 B 가 먼저 들어가면 반영이 입력 변경을 보고 STALE 로 닫는다 — relatedTaskPlanChangedDuringAnalysis_*)
        assertThat(column("validation", "status", id)).isEqualTo("PASS");
        assertThat(count("select count(*) from task_plan_revision where task_id = " + b[0])).isEqualTo(2);
    }

    @Test
    void newRelatedTaskDuringAnalysis_discardsResult() throws Exception {
        long[] a = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        long id = body(accept(a, member), 202).get("id").asLong();
        assertThat(count("select count(*) from validation_task_ref where validation_id = " + id)).isZero();
        doAnswer(inv -> {
            // AI 가 도는 동안 같은 항목을 보는 새 작업이 생긴다 — 분석에 들어가지 않은 관련 계획이다
            createTask(planJson("C", api.implementsRef("API-01"), true, null), member);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();

        assertThat(column("validation", "status", id)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", id)).isEqualTo("RELATED_PLAN_CHANGED");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
    }

    @Test
    void relatedTaskChangedWhileQueued_doesNotCallAi() throws Exception {
        long[] a = createTask(planJson("A", api.implementsRef("API-01"), true, null), member);
        long id = body(accept(a, member), 202).get("id").asLong();
        createTask(planJson("C", api.implementsRef("API-01"), true, null), member);   // 꺼내기 전에 관련 작업이 생긴다
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        doAnswer(inv -> {
            called.set(true);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();
        assertThat(called.get()).isFalse();
        assertThat(column("validation", "stale_reason", id)).isEqualTo("RELATED_PLAN_CHANGED");
    }

    @Test
    void worker_planChangedWhileQueued_doesNotCallAi() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        // 워커가 꺼내기 전에 계획 리비전이 바뀐다
        String plan = api.planJson("A2", api.readsRef("API-01"), "[]").trim();
        String revision = plan.substring(0, plan.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + t[2] + "\n}";
        HttpResponse<String> res = api.post(tasksPath() + "/" + t[0] + "/plan-revisions", revision, member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        doAnswer(inv -> {
            called.set(true);
            return aiResponse(inv.getArgument(0), List.of(), List.of());
        }).when(analyzer).analyze(any());

        worker.runOnce();
        assertThat(called.get()).isFalse();                 // 버릴 결과를 위해 모델을 부르지 않는다
        assertThat(column("validation", "status", id)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", id)).isEqualTo("PLAN_CHANGED");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("CANCELLED");
    }

    @Test
    void queuedValidation_taskCancelled_closesRunAsDiscarded() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        HttpResponse<String> cancel = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"CANCEL\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);
        assertThat(column("validation", "status", id)).isEqualTo("STALE");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("CANCELLED");
        // 접수 때 만든 실행이 RUNNING 으로 남지 않는다
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("DISCARDED");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void worker_expiredLease_isRecoveredAsErrorRetry_andLateResultIsIgnored() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        // 워커가 큐에서 꺼낸 뒤 AI 를 기다리다 죽었다 (재배포 등)
        JobLease lease = repository.lease(120).orElseThrow();
        assertThat(worker.recoverExpired()).isFalse();             // 리스가 아직 살아 있으면 건드리지 않는다
        api.jdbc.sql("update validation_job set lease_until = now() - interval '1 second' where id = :j")
                .param("j", lease.jobId()).update();

        assertThat(worker.recoverExpired()).isTrue();
        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("validation_lease_expired");
        assertThat(v.get("findings")).hasSize(1);
        assertThat(v.get("findings").get(0).get("kind").asText()).isEqualTo("ERROR");
        assertThat(v.get("findings").get(0).get("resolution").asText()).isEqualTo("RETRY");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("ERROR");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("FAILED");
        assertThat(worker.recoverExpired()).isFalse();
        assertThat(worker.runOnce()).isFalse();

        // 죽은 줄 알았던 워커의 결과가 늦게 와도 상태·발견 항목을 바꾸지 않는다. 다만 모델을 부른 사용량은 그 실행에 남긴다
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).isNull();
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> planCheck = new LinkedHashMap<>();
        planCheck.put("key", "plan-completeness");
        request.put("checks", List.of(planCheck));
        applier.applySuccess(lease, request, aiResponse(request, List.of(), List.of()), 0);
        assertThat(column("validation", "status", id)).isEqualTo("ERROR");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isEqualTo(1);
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("ERROR");
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).contains("llm-validate-x-attempt-1-call-1");
    }

    @Test
    void resultArrivingAfterLeaseExpiredButBeforeRecovery_isNotStoredAsPass() throws Exception {
        long[] t = createTask(planJson("A", api.readsRef("API-01"), true, "[{\"text\":\"x\"}]"), member);
        long id = body(accept(t, member), 202).get("id").asLong();
        JobLease lease = repository.lease(120).orElseThrow();
        // AI 응답을 기다리는 사이 리스가 만료됐지만 회수기는 아직 돌지 않았다
        api.jdbc.sql("update validation_job set lease_until = now() - interval '1 second' where id = :j")
                .param("j", lease.jobId()).update();
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> planCheck = new LinkedHashMap<>();
        planCheck.put("key", "plan-completeness");
        request.put("checks", List.of(planCheck));

        // 그 상태에서 정상 응답이 도착한다
        applier.applySuccess(lease, request, aiResponse(request, List.of(), List.of()), 0);

        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");                          // PASS 로 저장하지 않는다
        assertThat(v.get("error_code").asText()).isEqualTo("validation_lease_expired");
        assertThat(v.get("findings").get(0).get("resolution").asText()).isEqualTo("RETRY");
        assertThat(column("validation_run", "status", id(id, "validation_run"))).isEqualTo("ERROR");
        assertThat(column("validation_job", "status", id(id, "validation_job"))).isEqualTo("FAILED");
        assertThat(column("validation", "summary", id)).contains("llm-validate-x-attempt-1-call-1");   // 모델을 부른 사용량은 남긴다
        assertThat(column("validation_run", "usage", id(id, "validation_run"))).contains("llm-validate-x-attempt-1-call-1");
        assertThat(worker.recoverExpired()).isFalse();                                     // 이미 닫혀 회수할 것이 없다
    }

    @Test
    void worker_sendsItemSourceOnlyWithLocation_andStoresRequestSource() throws Exception {
        // 원문 문서 버전 하나에 항목 두 개를 잇는다: API-01 은 위치 없이(문서 버전만), API-02 는 위치까지
        Long doc;
        Optional<Long> existing = api.jdbc.sql("select id from document where project_id = :p order by id limit 1")
                .param("p", projectId).query(Long.class).optional();
        if (existing.isPresent()) {
            doc = existing.get();
        } else {
            doc = api.jdbc.sql("insert into document (project_id, type, title, created_by) values (:p, 'API_SPEC', 'API 명세', :a) returning id")
                    .param("p", projectId).param("a", leader).query(Long.class).single();
        }
        int docVersionNo = api.jdbc.sql("select coalesce(max(version_no), 0) + 1 from document_version where document_id = :d")
                .param("d", doc).query(Integer.class).single();
        Long docVersion = api.jdbc.sql("""
                        insert into document_version (document_id, version_no, status, storage_key, content_hash, approved_at)
                        values (:d, :n, 'APPROVED', :k, :h, now()) returning id
                        """)
                .param("d", doc).param("n", docVersionNo).param("k", "doc/" + doc + "/src-v" + docVersionNo)
                .param("h", "h-src-" + doc + "-" + docVersionNo).query(Long.class).single();
        Long v1 = versionWithSource("API-01", 2, docVersion, null);
        Long v2 = versionWithSource("API-02", 2, docVersion, "{\"location\": \"인증 API / 로그인 응답\"}");
        api.publish(2, Map.of("API-01", v1, "API-02", v2));
        long item2 = api.designItemId("API-02");
        String refs = "[{\"design_item_id\":\"" + api.designItemId("API-01") + "\",\"design_item_version_id\":\"" + v1
                + "\",\"relation\":\"IMPLEMENTS\"},{\"design_item_id\":\"" + item2 + "\",\"design_item_version_id\":\"" + v2
                + "\",\"relation\":\"IMPLEMENTS\"}]";
        long[] t = createTask(planJson("A", refs, true, null), member);
        long id = body(accept(t, member), 202).get("id").asLong();

        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        doAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            sent.set(req);
            // 요청에 온 출처를 인용하되 document_id 를 숫자로 바꿔 보낸다 — 저장은 요청의 출처 객체로 해야 한다
            Map<String, Object> cited = null;
            for (Object o : (List<?>) req.get("approved_items")) {
                Map<?, ?> item = (Map<?, ?>) o;
                if (String.valueOf(v2).equals(String.valueOf(item.get("id")))) {
                    cited = new LinkedHashMap<>((Map<String, Object>) item.get("source"));
                }
            }
            cited.put("document_id", Long.parseLong(String.valueOf(cited.get("document_id"))));
            Map<String, Object> f = candidate("design-" + item2, "MISMATCH", "BLOCKING", String.valueOf(item2), String.valueOf(v2));
            f.put("sources", List.of(cited));
            return aiResponse(req, List.of(f), List.of());
        }).when(analyzer).analyze(any());
        worker.runOnce();

        // 위치 없는 출처는 넣지 않고, 위치 있는 출처만 넣는다
        Map<String, Object> withoutLocation = null;
        Map<String, Object> withLocation = null;
        for (Object o : (List<?>) sent.get().get("approved_items")) {
            Map<String, Object> item = (Map<String, Object>) o;
            if (String.valueOf(v1).equals(String.valueOf(item.get("id")))) {
                withoutLocation = item;
            }
            if (String.valueOf(v2).equals(String.valueOf(item.get("id")))) {
                withLocation = item;
            }
        }
        assertThat(withoutLocation).doesNotContainKey("source");
        assertThat((Map<String, Object>) withLocation.get("source")).containsEntry("location", "인증 API / 로그인 응답")
                .containsEntry("document_version_id", String.valueOf(docVersion));

        JsonNode v = body(api.get("/api/projects/" + projectId + "/validations/" + id, member), 200);
        assertThat(v.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        JsonNode stored = v.get("findings").get(0).get("sources").get(0);
        assertThat(stored.get("document_id").isNumber()).isFalse();        // 요청에 넣은 출처 그대로 (문자열 ID)
        assertThat(stored.get("document_id").asText()).isEqualTo(String.valueOf(doc));
    }

    /** 원문 출처가 있는 새 승인 버전. locatorJson 이 null 이면 위치 없이 문서 버전만 */
    private Long versionWithSource(String key, int versionNo, Long documentVersionId, String locatorJson) {
        Long item = api.designItemId(key);
        api.jdbc.sql("""
                        insert into design_item_version (design_item_id, version_no, status, title, schema_version, content,
                                                         content_hash, origin, approved_at, source_document_version_id, source_locator)
                        values (:i, :n, 'APPROVED', :t, 1, '{}', :h, 'HUMAN', now(), :s, cast(:l as jsonb))
                        """)
                .param("i", item).param("n", versionNo).param("t", key + " v" + versionNo).param("h", "h-" + key + "-src-v" + versionNo)
                .param("s", documentVersionId).param("l", locatorJson, Types.VARCHAR).update();
        return api.jdbc.sql("select id from design_item_version where design_item_id = :i and version_no = :n")
                .param("i", item).param("n", versionNo).query(Long.class).single();
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
