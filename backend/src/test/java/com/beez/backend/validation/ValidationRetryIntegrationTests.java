package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.mcp.dto.RetryValidationArgs;
import com.beez.backend.mcp.service.ValidationMcpService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.service.AnalysisResultApplier;
import com.beez.backend.validation.service.FakeValidationAnalyzer;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검증 실행 재시도·중복 요청·횟수 제한 (S15P31A108-161): VAL-A04, MCP-14.
 * 분석 호출({@link ValidationAnalyzer})은 가짜로 바꿔 끼우고, 워커는 runOnce·recoverExpired 를 직접 부른다.
 * 가짜 빈 묶음은 {@link ValidationApiIntegrationTests} 와 같게 둬 스프링 문맥을 함께 쓴다.
 */
class ValidationRetryIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    ValidationWorker worker;

    @Autowired
    ValidationMcpService mcp;

    @MockitoSpyBean
    ValidationRepository repository;

    @Autowired
    AnalysisResultApplier applier;

    @MockitoBean
    ValidationAnalyzer analyzer;

    @MockitoBean
    HybridDocumentCandidateSearch search;

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
        searchWorks();
    }

    /** 근거 검색이 현재 기준으로 빈 결과를 돌려준다 */
    private void searchWorks() {
        doAnswer(inv -> {
            Long baseline = api.jdbc.sql("select current_baseline_id from project where id=:id")
                    .param("id", projectId).query(Long.class).single();
            return new HybridDocumentCandidateSearch.Result(baseline.toString(), List.of(), false, false);
        }).when(search).search(any(), org.mockito.ArgumentMatchers.eq(projectId), any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(100));
    }

    /** 근거 검색이 장애로 실패한다 — 워커는 AI 를 부르기 전에 evidence_fetch_failed 로 닫는다 */
    private void searchFails() {
        doThrow(new IllegalStateException("검색 장애 (시험용)")).when(search).search(any(), org.mockito.ArgumentMatchers.eq(projectId), any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(100));
    }

    /** 계획을 통째로 바꾼다 (TSK-A04). 첫 수정이라 expected_revision_no 는 1 */
    private HttpResponse<String> revisePlan(long[] t, String planJson) throws Exception {
        String plan = planJson.trim();
        String body = plan.substring(0, plan.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + t[2] + "\n}";
        return api.post(tasksPath() + "/" + t[0] + "/plan-revisions", body, member);
    }

    /** 두 참조를 이 순서대로 담은 설계 참조 목록 JSON */
    private static String refs(String first, String second) {
        String a = first.trim();
        String b = second.trim();
        return a.substring(0, a.lastIndexOf(']')) + ", " + b.substring(b.indexOf('[') + 1);
    }

    /** 계획 본문 끝에 칸을 더하고 계획을 고친다 (TSK-A04) */
    private HttpResponse<String> revise(long[] t, String extraFields) throws Exception {
        String plan = api.planJson("A", api.implementsRef("API-01"), "[]").trim();
        String body = plan.substring(0, plan.lastIndexOf('}')) + extraFields
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + t[2] + "\n}";
        return api.post(tasksPath() + "/" + t[0] + "/plan-revisions", body, member);
    }

    // ---- 도우미 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    private String validationPath(long id) {
        return "/api/projects/" + projectId + "/validations/" + id;
    }

    /** 의미 분석이 필요하고, 결과 후보가 없으면 PASS 로 끝나는 계획 (ValidationApiIntegrationTests.worker_complete_withoutCandidates_isPass 와 같다) */
    private long[] passableTask(String title) throws Exception {
        if (api.jdbc.sql("select count(*) from baseline where project_id = :p and version_no = 2").param("p", projectId)
                .query(Integer.class).single() == 0) {
            provideCompleteApiContent();
        }
        return createTask(api.planJson(title, api.implementsRef("API-01"), "[]"), member);
    }

    /** API-01 의 내용을 채운 버전 2 를 기준 2 로 발행한다 */
    private void provideCompleteApiContent() {
        Long version = api.jdbc.sql("""
                insert into design_item_version (design_item_id, version_no, status, title, schema_version,
                    content, content_hash, origin, approved_at)
                values (:item, 2, 'APPROVED', 'API-01 complete', 1, cast(:content as jsonb), 'complete-API-01', 'HUMAN', now())
                returning id
                """).param("item", api.designItemId("API-01"))
                .param("content", "{\"request_fields\":[],\"responses\":[{\"fields\":[]}]}").query(Long.class).single();
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("API-01", version);
        versions.put("API-02", api.currentVersionId("API-02"));
        api.publish(2, versions);
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

    private HttpResponse<String> retry(long validationId, int expectedAttempt, Long account) throws Exception {
        return api.post(validationPath(validationId) + "/retry", "{\"expected_attempt\":" + expectedAttempt + "}", account);
    }

    private JsonNode body(HttpResponse<String> res, int status) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return JSON.readTree(res.body());
    }

    private JsonNode validation(long id) {
        try {
            return body(api.get(validationPath(id), member), 200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertError(HttpResponse<String> res, int status, String code) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        assertThat(res.body()).contains(code);
    }

    private Integer count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    private String runStatus(long validationId, int attempt) {
        return api.jdbc.sql("select status from validation_run where validation_id = :v and attempt = :a")
                .param("v", validationId).param("a", attempt).query(String.class).single();
    }

    private String jobStatus(long validationId) {
        return api.jdbc.sql("select status from validation_job where validation_id = :v").param("v", validationId)
                .query(String.class).single();
    }

    private String summary(long validationId) {
        return api.jdbc.sql("select summary::text from validation where id = :v").param("v", validationId)
                .query(String.class).single();
    }

    private static String usageKey(long validationId, int attempt) {
        return "llm-validate-" + validationId + "-attempt-" + attempt + "-call-1";
    }

    /** 명세 Usage 한 항목 */
    private static Map<String, Object> usageEntry(String requestKey, boolean failed) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("request_key", requestKey);
        u.put("feature", "TASK_VALIDATION");
        u.put("model", "test-model");
        u.put("provider", "test-provider");
        u.put("tokens_known", true);
        u.put("input_tokens", 10);
        u.put("output_tokens", 5);
        u.put("latency_ms", 321);
        u.put("failed", failed);
        return u;
    }

    /** AI 성공 응답. unassessed 가 있으면 PARTIAL. 사용량 키는 요청의 검증 번호·시도 번호로 만든다 */
    private static Map<String, Object> aiResponse(Map<String, Object> request, List<Map<String, Object>> unassessed) {
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> u : unassessed) {
            skipped.add(String.valueOf(u.get("check_key")));
        }
        List<String> checked = new ArrayList<>();
        for (Object c : (List<?>) request.get("checks")) {
            String key = String.valueOf(((Map<?, ?>) c).get("key"));
            if (!skipped.contains(key)) {
                checked.add(key);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (unassessed.isEmpty()) {
            result.put("analysis_status", "COMPLETE");
        } else {
            result.put("analysis_status", "PARTIAL");
        }
        result.put("checked_keys", checked);
        result.put("unassessed", unassessed);
        result.put("findings", List.of());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", "test-model");
        long validationId = Long.parseLong(String.valueOf(request.get("validation_id")));
        int attempt = ((Number) request.get("attempt")).intValue();
        response.put("usage", List.of(usageEntry(usageKey(validationId, attempt), false)));
        return response;
    }

    /** 다음 runOnce 에서 AI 가 시간 초과로 실패한다 (그 시도의 실패 호출 사용량을 오류 본문에 넣어 준다) */
    private void nextCallTimesOut() throws Exception {
        // doAnswer 꼴로 바꿔 끼운다 — when(...).thenAnswer 꼴은 바꿔 끼우는 순간 앞에 끼운 가짜 응답(예외)을 실제로 부른다
        doAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            long validationId = Long.parseLong(String.valueOf(req.get("validation_id")));
            int attempt = ((Number) req.get("attempt")).intValue();
            throw new ValidationAnalyzer.AnalyzerException("ai_timeout", "시간 초과", null,
                    List.of(usageEntry(usageKey(validationId, attempt), true)));
        }).when(analyzer).analyze(any());
    }

    /** 접수 → 시간 초과로 ERROR(RETRY) 가 된 검증 번호 */
    private long timedOut(long[] t) throws Exception {
        long id = body(accept(t, member), 202).get("id").asLong();
        nextCallTimesOut();
        assertThat(worker.runOnce()).isTrue();
        JsonNode v = validation(id);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("ai_timeout");
        return id;
    }

    // ---- 재시도 흐름 ----

    @Test
    void timeoutThenRetry_runsTheNextAttemptAndPasses_keepingEachAttemptsHistoryAndCost() throws Exception {
        long[] t = passableTask("A");
        long id = timedOut(t);

        JsonNode reopened = body(retry(id, 1, member), 202);
        assertThat(reopened.get("status").asText()).isEqualTo("RUNNING");
        assertThat(reopened.get("current_attempt").asInt()).isEqualTo(2);
        assertThat(reopened.has("error_code")).isFalse();
        assertThat(reopened.get("findings")).isEmpty();                 // 지난 시도의 오류 항목은 응답에서 빠진다
        assertThat(reopened.has("finished_at")).isFalse();
        assertThat(runStatus(id, 1)).isEqualTo("TIMEOUT");              // 지난 시도의 실행은 그대로
        assertThat(runStatus(id, 2)).isEqualTo("RUNNING");
        assertThat(jobStatus(id)).isEqualTo("QUEUED");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isEqualTo(1);   // 이력은 남는다
        assertThat(summary(id)).doesNotContain("error_code").doesNotContain(usageKey(id, 1)).contains("\"attempt\": 2");

        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        doAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            sent.set(req);
            return aiResponse(req, List.of());
        }).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(sent.get().get("attempt")).isEqualTo(2);

        JsonNode v = validation(id);
        assertThat(v.get("status").asText()).isEqualTo("PASS");
        assertThat(v.get("current_attempt").asInt()).isEqualTo(2);
        assertThat(v.get("findings")).isEmpty();
        assertThat(runStatus(id, 2)).isEqualTo("OK");
        assertThat(jobStatus(id)).isEqualTo("DONE");
        // 두 시도의 비용이 각각 한 번씩 집계된다
        assertThat(count("select count(*) from llm_usage where request_key = '" + usageKey(id, 1) + "'")).isEqualTo(1);
        assertThat(count("select count(*) from llm_usage where request_key = '" + usageKey(id, 2) + "'")).isEqualTo(1);

        // 다시 돌려 통과한 검증으로 작업을 시작할 수 있다
        HttpResponse<String> start = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2]
                        + ",\"validation_id\":\"" + id + "\"}", member);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(200);
    }

    @Test
    void sameRequestSentAgain_returnsTheAttemptItOpened_andNeverOpensAnother() throws Exception {
        long id = timedOut(passableTask("A"));

        JsonNode first = body(retry(id, 1, member), 202);
        JsonNode again = body(retry(id, 1, member), 202);              // 응답을 잃고 다시 보냄
        assertThat(again.get("current_attempt").asInt()).isEqualTo(2);
        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(2);
        assertThat(count("select count(*) from validation_job where validation_id = " + id)).isEqualTo(1);

        // 아직 도는 시도를 또 다시 돌릴 수는 없다. 없는 시도 번호는 충돌
        assertError(retry(id, 2, member), 409, "invalid_transition");
        assertError(retry(id, 3, member), 409, "revision_conflict");

        // 두 번째 시도도 실패한 뒤, 첫 요청을 또 보내도 새 시도를 만들지 않는다 (그 요청이 연 시도 2 가 있는 지금 상태)
        nextCallTimesOut();
        worker.runOnce();
        JsonNode late = body(retry(id, 1, member), 202);
        assertThat(late.get("status").asText()).isEqualTo("ERROR");
        assertThat(late.get("current_attempt").asInt()).isEqualTo(2);
        assertThat(late.get("findings")).hasSize(1);                    // 현재 시도(2)의 오류 항목만
        assertThat(Long.parseLong(late.get("findings").get(0).get("validation_run_id").asText()))
                .isEqualTo(api.jdbc.sql("select id from validation_run where validation_id = :v and attempt = 2")
                        .param("v", id).query(Long.class).single());
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isEqualTo(2);

        // 시도 3 을 연 뒤에는 시도 1 의 요청이 "바로 앞 요청의 재전송" 이 아니다 — 그 사이 다른 재시도가 있었다
        assertThat(body(retry(id, 2, member), 202).get("current_attempt").asInt()).isEqualTo(3);
        assertError(retry(id, 1, member), 409, "revision_conflict");
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(3);
    }

    @Test
    void twoRetriesAtTheSameTime_openOnlyOneAttempt() throws Exception {
        long id = timedOut(passableTask("A"));
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<HttpResponse<String>> a = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                return retry(id, 1, member);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        CompletableFuture<HttpResponse<String>> b = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
                return retry(id, 1, member);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        go.countDown();
        assertThat(body(a.get(20, TimeUnit.SECONDS), 202).get("current_attempt").asInt()).isEqualTo(2);
        assertThat(body(b.get(20, TimeUnit.SECONDS), 202).get("current_attempt").asInt()).isEqualTo(2);
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(2);
    }

    @Test
    void lateResultOfTheEarlierAttempt_isIgnored_afterRetry() throws Exception {
        long[] t = passableTask("A");
        long id = body(accept(t, member), 202).get("id").asLong();
        // 워커가 AI 를 기다리다 죽었다 (재배포) → 회수되어 ERROR(RETRY)
        JobLease oldLease = repository.lease(120).orElseThrow();
        api.jdbc.sql("update validation_job set lease_until = now() - interval '1 second' where id = :j")
                .param("j", oldLease.jobId()).update();
        assertThat(worker.recoverExpired()).isTrue();
        assertThat(validation(id).get("error_code").asText()).isEqualTo("validation_lease_expired");

        body(retry(id, 1, member), 202);
        // 죽은 줄 알았던 워커의 시도 1 결과가 이제 도착한다 → 시도 2 를 건드리지 않는다. 사용량만 시도 1 에 남는다
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("validation_id", String.valueOf(id));
        request.put("attempt", 1);
        request.put("checks", List.of(Map.of("key", "plan-completeness")));
        applier.applySuccess(oldLease, request, aiResponse(request, List.of()), 0);
        JsonNode v = validation(id);
        assertThat(v.get("status").asText()).isEqualTo("RUNNING");
        assertThat(v.get("current_attempt").asInt()).isEqualTo(2);
        assertThat(runStatus(id, 1)).isEqualTo("ERROR");
        assertThat(runStatus(id, 2)).isEqualTo("RUNNING");
        assertThat(jobStatus(id)).isEqualTo("QUEUED");
        assertThat(count("select count(*) from llm_usage where request_key = '" + usageKey(id, 1) + "'")).isEqualTo(1);

        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of())).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(validation(id).get("status").asText()).isEqualTo("PASS");
    }

    // ---- 거부 ----

    @Test
    void refusesWrongCallerAndBadInput() throws Exception {
        long id = timedOut(passableTask("A"));
        assertError(retry(id, 1, leader), 403, "forbidden");           // 팀장도 담당자가 아니면 안 된다
        assertThat(retry(id, 1, outsider).statusCode()).isEqualTo(404);
        assertThat(retry(id, 1, null).statusCode()).isEqualTo(401);
        assertThat(api.post("/api/projects/" + (projectId + 99) + "/validations/" + id + "/retry", "{\"expected_attempt\":1}", member)
                .statusCode()).isEqualTo(404);
        assertThat(retry(id + 999, 1, member).statusCode()).isEqualTo(404);
        assertError(retry(id, 0, member), 400, "validation_failed");
        assertError(api.post(validationPath(id) + "/retry", "{}", member), 400, "validation_failed");
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(1);
        assertThat(jobStatus(id)).isEqualTo("FAILED");
    }

    @Test
    void refusesWhenRetryingCannotHelp() throws Exception {
        // 근거 부족(PARTIAL) — 사람이 보완해야 한다
        long[] partial = createTask(api.planJson("P", api.readsRef("API-01"), "[]")
                .replace("\"unlinked\": []", "\"unlinked\": [{\"text\":\"x\"}]"), member);
        long partialId = body(accept(partial, member), 202).get("id").asLong();
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of(
                Map.of("check_key", "plan-completeness", "reason", "EVIDENCE_INSUFFICIENT", "message", "근거 부족"))))
                .when(analyzer).analyze(any());
        worker.runOnce();
        assertThat(validation(partialId).get("error_code").asText()).isEqualTo("ai_evidence_insufficient");
        assertError(retry(partialId, 1, member), 409, "invalid_transition");

        // 끝난(PASS) 검증
        long[] t = passableTask("A");
        long passed = body(accept(t, member), 202).get("id").asLong();
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of())).when(analyzer).analyze(any());
        worker.runOnce();
        assertThat(validation(passed).get("status").asText()).isEqualTo("PASS");
        assertError(retry(passed, 1, member), 409, "invalid_transition");
        assertError(retry(passed, 0, member), 400, "validation_failed");

        // 취소된 작업
        long[] t3 = passableTask("C");
        long cancelledId = timedOut(t3);
        api.jdbc.sql("update task set status = 'CANCELLED', cancelled_at = now() where id = :t").param("t", t3[0]).update();
        assertError(retry(cancelledId, 1, member), 409, "invalid_transition");

        // 그 작업에 더 최근 검증이 있음 (큐에 남는 새 검증은 이 시험의 마지막에 만든다)
        long[] t2 = passableTask("B");
        long older = timedOut(t2);
        long newer = body(accept(t2, member), 202).get("id").asLong();
        assertThat(newer).isGreaterThan(older);
        assertError(retry(older, 1, member), 409, "invalid_transition");

        assertThat(count("select count(*) from validation_run where validation_id in (" + partialId + "," + older + "," + cancelledId + ")"))
                .isEqualTo(3);
    }

    @Test
    void refusesWhenThePlanOrTheReferencedDesignChanged() throws Exception {
        // 계획 내용이 바뀜 → 새 검증
        long[] t = passableTask("A");
        long id = timedOut(t);
        String revised = api.planJson("A", api.implementsRef("API-01"), "[]").trim()
                .replace("\"goal\": \"목표\"", "\"goal\": \"다른 목표\"");
        assertThat(revised).contains("다른 목표");
        String body = revised.substring(0, revised.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + t[2] + "\n}";
        HttpResponse<String> changed = api.post(tasksPath() + "/" + t[0] + "/plan-revisions", body, member);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        assertError(retry(id, 1, member), 409, "revision_conflict");
        assertThat(validation(id).get("status").asText()).isEqualTo("ERROR");
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(1);

        // 참조한 설계 항목의 새 버전이 발행됨 → 새 검증
        long[] t2 = passableTask("B");
        long id2 = timedOut(t2);
        Map<String, Long> versions = new LinkedHashMap<>();
        versions.put("API-01", api.newVersion("API-01", 3));
        versions.put("API-02", api.currentVersionId("API-02"));
        api.publish(3, versions);
        assertError(retry(id2, 1, member), 409, "revision_conflict");
        assertThat(count("select count(*) from validation_run where validation_id = " + id2)).isEqualTo(1);
        assertThat(jobStatus(id2)).isEqualTo("FAILED");
    }

    // ---- 횟수 제한 ----

    @Test
    void sameInputCanBeAnalysedOnlyThreeTimes_serverInterruptionsDoNotCount() throws Exception {
        long[] t = passableTask("A");
        long id = body(accept(t, member), 202).get("id").asLong();
        // 시도 1: 재배포로 회수 — 세지 않는다
        JobLease lease = repository.lease(120).orElseThrow();
        api.jdbc.sql("update validation_job set lease_until = now() - interval '1 second' where id = :j")
                .param("j", lease.jobId()).update();
        assertThat(worker.recoverExpired()).isTrue();

        // 시도 2·3·4: 시간 초과 — 세 번
        for (int attempt = 1; attempt <= 3; attempt++) {
            body(retry(id, attempt, member), 202);
            nextCallTimesOut();
            assertThat(worker.runOnce()).isTrue();
            assertThat(validation(id).get("error_code").asText()).isEqualTo("ai_timeout");
        }
        assertError(retry(id, 4, member), 409, "validation_attempt_limit");
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(4);
        // 새 검증으로 돌아가지도 못한다
        assertError(accept(t, member), 409, "validation_attempt_limit");
        assertThat(count("select count(*) from validation where task_id = " + t[0])).isEqualTo(1);

        // 계획을 고치면 다른 입력이라 다시 검증할 수 있다
        String revised = api.planJson("A", api.implementsRef("API-01"), "[]").trim()
                .replace("\"goal\": \"목표\"", "\"goal\": \"다른 목표\"");
        String body = revised.substring(0, revised.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + t[2] + "\n}";
        HttpResponse<String> changed = api.post(tasksPath() + "/" + t[0] + "/plan-revisions", body, member);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", t[0])
                .query(Long.class).single();
        HttpResponse<String> fresh = accept(new long[] {t[0], revision, TaskApiSupport.longField(changed.body(), "lock_version")}, member);
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(202);
    }

    @Test
    void fakeAnalyzerRunsDoNotCount_soAfterSwitchingToTheRealAiTheSamePlanCanBeValidatedAgain() throws Exception {
        long[] t = passableTask("A");
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of())).when(analyzer).analyze(any());
        for (int i = 0; i < 3; i++) {
            long id = body(accept(t, member), 202).get("id").asLong();
            assertThat(worker.runOnce()).isTrue();
            assertThat(validation(id).get("status").asText()).isEqualTo("PASS");
        }
        // 실제 분석기로 세 번 돌았으면 같은 입력으로는 더 돌리지 않는다
        assertError(accept(t, member), 409, "validation_attempt_limit");

        // 같은 세 번이 가짜 분석기(개발 서버)였다면 세지 않는다 — 실제 AI 로 바꾼 뒤 새 검증을 받는다
        api.jdbc.sql("""
                update validation_run set analyzer_version = :fake
                 where validation_id in (select id from validation where task_id = :t)
                """).param("fake", FakeValidationAnalyzer.MODEL).param("t", t[0]).update();
        assertThat(accept(t, member).statusCode()).isEqualTo(202);
    }

    @Test
    void failuresBeforeTheModelIsCalledDoNotCount() throws Exception {
        long[] t = passableTask("A");
        long id = body(accept(t, member), 202).get("id").asLong();
        // 시도 1: 근거 검색 장애 — AI 를 부르지 않았다
        searchFails();
        assertThat(worker.runOnce()).isTrue();
        assertThat(validation(id).get("error_code").asText()).isEqualTo("evidence_fetch_failed");
        searchWorks();
        // 시도 2·3·4: AI 서버에 연결하지 못함(요청이 닿지 않음), 사용량 없음
        doAnswer(inv -> {
            throw new ValidationAnalyzer.AnalyzerException("ai_unavailable", "연결 실패", null, null);
        }).when(analyzer).analyze(any());
        for (int attempt = 1; attempt <= 3; attempt++) {
            body(retry(id, attempt, member), 202);
            assertThat(worker.runOnce()).isTrue();
            assertThat(validation(id).get("error_code").asText()).isEqualTo("ai_unavailable");
        }
        // 아직 한 번도 세지 않았으므로 다시 돌릴 수 있다
        assertThat(body(retry(id, 4, member), 202).get("current_attempt").asInt()).isEqualTo(5);
    }

    @Test
    void readTimeoutWithoutUsageStillCounts_becauseTheRequestReachedTheAi() throws Exception {
        long[] t = passableTask("A");
        long id = body(accept(t, member), 202).get("id").asLong();
        // 요청을 보낸 뒤 응답을 못 받음(읽기 시간 초과) — 모델이 돌았을 수 있어 사용량이 없어도 센다
        doAnswer(inv -> {
            throw new ValidationAnalyzer.AnalyzerException("ai_timeout", "AI 호출이 시간 안에 응답하지 않았습니다", null, null);
        }).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        for (int attempt = 1; attempt <= 2; attempt++) {
            body(retry(id, attempt, member), 202);
            assertThat(worker.runOnce()).isTrue();
            assertThat(validation(id).get("error_code").asText()).isEqualTo("ai_timeout");
        }
        assertThat(api.jdbc.sql("select count(*) from validation_run where validation_id = :v and usage is null")
                .param("v", id).query(Integer.class).single()).isEqualTo(3);
        assertError(retry(id, 3, member), 409, "validation_attempt_limit");
        assertError(accept(t, member), 409, "validation_attempt_limit");
    }

    @Test
    void designReferenceOrderDoesNotChangeTheInputForTheLimit() throws Exception {
        provideCompleteApiContent();
        String forward = refs(api.implementsRef("API-01"), api.readsRef("API-02"));
        long[] t = createTask(api.planJson("R", forward, "[]"), member);
        long id = timedOut(t);
        for (int attempt = 1; attempt <= 2; attempt++) {
            body(retry(id, attempt, member), 202);
            nextCallTimesOut();
            assertThat(worker.runOnce()).isTrue();
        }
        assertError(accept(t, member), 409, "validation_attempt_limit");

        // 같은 참조를 반대 순서로 저장한 계획 — 내용이 같으니 같은 입력으로 센다
        String backward = refs(api.readsRef("API-02"), api.implementsRef("API-01"));
        HttpResponse<String> reordered = revisePlan(t, api.planJson("R", backward, "[]"));
        assertThat(reordered.statusCode()).as(reordered.body()).isEqualTo(201);
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", t[0])
                .query(Long.class).single();
        assertThat(revision).isNotEqualTo(t[1]);
        HttpResponse<String> again = accept(new long[] {t[0], revision, TaskApiSupport.longField(reordered.body(), "lock_version")}, member);
        assertError(again, 409, "validation_attempt_limit");
    }

    @Test
    void deltaRetry_isNotBlockedByANewerPlanValidation_andCountsTowardTheLimit() throws Exception {
        long[] t = passableTask("D");
        long pass = api.passValidation(t[0]);
        HttpResponse<String> started = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2]
                        + ",\"validation_id\":\"" + pass + "\"}", member);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        HttpResponse<String> done = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"COMPLETED\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":"
                        + TaskApiSupport.longField(started.body(), "lock_version")
                        + ",\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]}", member);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long delta = TaskApiSupport.longField(done.body(), "delta_validation_id");
        long lock = TaskApiSupport.longField(done.body(), "lock_version");
        nextCallTimesOut();
        assertThat(worker.runOnce()).isTrue();
        assertThat(validation(delta).get("status").asText()).isEqualTo("ERROR");
        assertThat(validation(delta).get("trigger").asText()).isEqualTo("DELTA");

        // 완료 대기 중 새 계획 검증을 받는다 (RESUME 준비). 이것이 DELTA 재시도를 막지 않는다
        long agent = body(accept(new long[] {t[0], t[1], lock}, member), 202).get("id").asLong();
        assertThat(agent).isGreaterThan(delta);
        assertThat(body(retry(delta, 1, member), 202).get("current_attempt").asInt()).isEqualTo(2);
        // 작업 상세는 기다리는 DELTA 와 현재 계획의 가장 최근 검증(새 계획 검증)을 함께 싣는다
        JsonNode detail = body(api.get(tasksPath() + "/" + t[0], member), 200);
        assertThat(detail.get("delta_validation_id").asText()).isEqualTo(String.valueOf(delta));
        assertThat(detail.get("validation").get("id").asText()).isEqualTo(String.valueOf(agent));

        // 큐: 새 계획 검증이 먼저, 다시 연 DELTA 가 다음 — 둘 다 시간 초과
        assertThat(worker.runOnce()).isTrue();
        assertThat(worker.runOnce()).isTrue();
        assertThat(validation(agent).get("error_code").asText()).isEqualTo("ai_timeout");
        assertThat(validation(delta).get("error_code").asText()).isEqualTo("ai_timeout");
        // DELTA 재시도도 센다 (서버가 접수한 첫 실행 포함 3번)
        body(retry(delta, 2, member), 202);
        assertThat(worker.runOnce()).isTrue();
        assertError(retry(delta, 3, member), 409, "validation_attempt_limit");
        // 계획 검증은 다른 입력이라 따로 센다 (1번 썼다)
        assertThat(body(retry(agent, 1, member), 202).get("current_attempt").asInt()).isEqualTo(2);
    }

    @Test
    void changingTheContextPacketMakesItANewInputForTheLimit() throws Exception {
        long[] t = passableTask("A");
        long id = timedOut(t);
        for (int attempt = 1; attempt <= 2; attempt++) {
            body(retry(id, attempt, member), 202);
            nextCallTimesOut();
            assertThat(worker.runOnce()).isTrue();
        }
        assertError(retry(id, 3, member), 409, "validation_attempt_limit");
        assertError(accept(t, member), 409, "validation_attempt_limit");

        // 계획 내용은 그대로 두고 컨텍스트 패킷만 붙인다 → 실제 검증 근거가 바뀌었다
        long packet = api.jdbc.sql("""
                insert into dev_context_packet(project_id, request, baseline_id, content, content_hash)
                values (:p, '검증 패킷', (select current_baseline_id from project where id = :p),
                        '{"constraints":["추가 제약"],"unresolved":[]}'::jsonb, 'packet-for-retry-limit')
                returning id
                """).param("p", projectId).query(Long.class).single();
        HttpResponse<String> changed = revise(t, ", \"context_packet_id\": \"" + packet + "\"");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", t[0])
                .query(Long.class).single();
        assertThat(revision).isNotEqualTo(t[1]);
        // 옛 검증은 입력이 바뀌어 다시 돌릴 수 없고, 새 검증은 다른 입력이라 처음부터 센다
        assertError(retry(id, 3, member), 409, "revision_conflict");
        HttpResponse<String> fresh = accept(new long[] {t[0], revision, TaskApiSupport.longField(changed.body(), "lock_version")}, member);
        assertThat(fresh.statusCode()).as(fresh.body()).isEqualTo(202);
    }

    // ---- 실패 뒤 작업 상태 ----

    @Test
    void failedValidationCannotStartTheTask_andTheTaskStaysReady() throws Exception {
        long[] t = passableTask("A");
        long id = timedOut(t);
        // 작업 상세(TSK-A03)가 현재 계획의 검증을 싣는다 — 화면은 이 값으로 오류 뒤 재시도 버튼을 그린다
        JsonNode detail = body(api.get(tasksPath() + "/" + t[0], member), 200);
        assertThat(detail.get("validation_status").asText()).isEqualTo("ERROR");
        assertThat(detail.get("validation").get("id").asText()).isEqualTo(String.valueOf(id));
        assertThat(detail.get("validation").get("current_attempt").asInt()).isEqualTo(1);
        assertThat(detail.get("validation").get("findings").get(0).get("resolution").asText()).isEqualTo("RETRY");
        HttpResponse<String> start = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2]
                        + ",\"validation_id\":\"" + id + "\"}", member);
        assertError(start, 409, "validation_not_passed");
        // 다시 돌리는 중(RUNNING)에도 시작할 수 없다
        body(retry(id, 1, member), 202);
        JsonNode reopened = body(api.get(tasksPath() + "/" + t[0], member), 200);
        assertThat(reopened.get("validation").get("status").asText()).isEqualTo("RUNNING");
        assertThat(reopened.get("validation").get("current_attempt").asInt()).isEqualTo(2);
        HttpResponse<String> whileRunning = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2]
                        + ",\"validation_id\":\"" + id + "\"}", member);
        assertError(whileRunning, 409, "validation_not_passed");
        Map<String, Object> task = api.jdbc.sql("select status, lock_version, started_validation_id from task where id = :t")
                .param("t", t[0]).query().singleRow();
        assertThat(task.get("status")).isEqualTo("READY");
        assertThat(((Number) task.get("lock_version")).longValue()).isEqualTo(t[2]);   // 실패·재시도는 작업을 바꾸지 않는다
        assertThat(task.get("started_validation_id")).isNull();
    }

    // ---- MCP-14 ----

    @Test
    void mcp_retryValidation_sameKeyReplays_andFollowsTheSameRules() throws Exception {
        long id = timedOut(passableTask("A"));
        CallerContext agent = CallerContext.agent(member, api.agentTokenId());
        RetryValidationArgs args = new RetryValidationArgs(String.valueOf(id), 1, "r-1");
        ValidationResponse first = mcp.retryValidation(agent, projectId, args);
        assertThat(first.status()).isEqualTo("RUNNING");
        assertThat(first.currentAttempt()).isEqualTo(2);
        ValidationResponse again = mcp.retryValidation(agent, projectId, args);
        assertThat(again.currentAttempt()).isEqualTo(2);
        // 키가 달라도 같은 시도 번호면 새 시도를 만들지 않는다
        assertThat(mcp.retryValidation(agent, projectId, new RetryValidationArgs(String.valueOf(id), 1, "r-2")).currentAttempt())
                .isEqualTo(2);
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(2);
        // 워커는 다시 돌리는 사람(에이전트)의 권한으로 검색한다
        assertThat(summary(id)).contains("\"kind\": \"AGENT\"");

        // 같은 키에 다른 내용
        assertThatThrownBy(() -> mcp.retryValidation(agent, projectId, new RetryValidationArgs(String.valueOf(id), 2, "r-1")))
                .isInstanceOf(ApiException.class).hasMessageContaining("같은 요청 키");
        // 키 없음
        assertThatThrownBy(() -> mcp.retryValidation(agent, projectId, new RetryValidationArgs(String.valueOf(id), 2, null)))
                .isInstanceOf(ApiException.class);
        // 담당자가 아닌 토큰
        assertThatThrownBy(() -> mcp.retryValidation(CallerContext.agent(leader, api.leaderAgentTokenId()), projectId, args))
                .isInstanceOf(ApiException.class);
        // 도는 중인 시도는 다시 돌릴 수 없다
        assertThatThrownBy(() -> mcp.retryValidation(agent, projectId, new RetryValidationArgs(String.valueOf(id), 2, "r-3")))
                .isInstanceOf(ApiException.class).hasMessageContaining("실패한 검증만");
    }
}
