package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
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
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 검증 장애·동시성 시험 (S15P31A108-65). 다른 시험이 아직 다루지 않은 경우만 모았다 — 시나리오와 기존 시험의 대응은
 * docs/design/12-validation-resilience.md. 분석 호출({@link ValidationAnalyzer})은 가짜로 바꿔 끼우고, 워커는 runOnce·recoverExpired 를
 * 직접 부른다. 가짜 빈 묶음은 {@link ValidationApiIntegrationTests} 와 같게 둬 스프링 문맥을 함께 쓴다.
 */
class ValidationResilienceIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    ValidationWorker worker;

    @MockitoSpyBean
    ValidationRepository repository;

    @MockitoBean
    ValidationAnalyzer analyzer;

    @MockitoBean
    HybridDocumentCandidateSearch search;

    TaskApiSupport api;
    Long projectId;
    Long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
        doAnswer(inv -> {
            Long baseline = api.jdbc.sql("select current_baseline_id from project where id=:id")
                    .param("id", projectId).query(Long.class).single();
            return new HybridDocumentCandidateSearch.Result(baseline.toString(), List.of(), false, false);
        }).when(search).search(any(), org.mockito.ArgumentMatchers.eq(projectId), any(),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq(100));
    }

    // ---- 도우미 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 의미 분석이 필요하고, 결과 후보가 없으면 PASS 로 끝나는 작업 → {taskId, revisionId, lockVersion} */
    private long[] passableTask(String title) throws Exception {
        if (api.jdbc.sql("select count(*) from baseline where project_id = :p and version_no = 2").param("p", projectId)
                .query(Integer.class).single() == 0) {
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
        HttpResponse<String> res = api.post(tasksPath(), api.planJson(title, api.implementsRef("API-01"), "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        return new long[] {taskId, revisionId, TaskApiSupport.longField(res.body(), "lock_version")};
    }

    private long accept(long[] t) throws Exception {
        HttpResponse<String> res = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        return TaskApiSupport.longField(res.body(), "id");
    }

    private JsonNode validation(long id) throws Exception {
        HttpResponse<String> res = api.get("/api/projects/" + projectId + "/validations/" + id, member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private Integer count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    private String one(String sql) {
        return api.jdbc.sql(sql).query(String.class).single();
    }

    private static String usageKey(long validationId, int attempt) {
        return "llm-validate-" + validationId + "-attempt-" + attempt + "-call-1";
    }

    /** AI 성공 응답 (후보 없음, 모든 검사 완료). 사용량 키는 요청의 검증 번호·시도 번호로 만든다 */
    private static Map<String, Object> aiResponse(Map<String, Object> request) {
        List<String> checked = new ArrayList<>();
        for (Object c : (List<?>) request.get("checks")) {
            checked.add(String.valueOf(((Map<?, ?>) c).get("key")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis_status", "COMPLETE");
        result.put("checked_keys", checked);
        result.put("unassessed", List.of());
        result.put("findings", List.of());
        Map<String, Object> usage = new LinkedHashMap<>();
        long validationId = Long.parseLong(String.valueOf(request.get("validation_id")));
        int attempt = ((Number) request.get("attempt")).intValue();
        usage.put("request_key", usageKey(validationId, attempt));
        usage.put("feature", "TASK_VALIDATION");
        usage.put("model", "test-model");
        usage.put("provider", "test-provider");
        usage.put("tokens_known", true);
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);
        usage.put("latency_ms", 321);
        usage.put("failed", false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", "test-model");
        response.put("usage", List.of(usage));
        return response;
    }

    /** 분석 호출이 들어오면 entered 를 내리고 release 까지 붙잡은 뒤 성공 응답을 돌려준다 (모델이 도는 동안을 흉내) */
    private void analyzerHoldsUntil(CountDownLatch entered, CountDownLatch release, AtomicInteger calls) throws Exception {
        doAnswer(inv -> {
            calls.incrementAndGet();
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return aiResponse(inv.getArgument(0));
        }).when(analyzer).analyze(any());
    }

    // ---- 동시 실행 ----

    @Test
    void twoWorkersPollingAtTheSameTime_takeTheJobOnce_andCallTheModelOnce() throws Exception {
        long id = accept(passableTask("A"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        analyzerHoldsUntil(entered, release, calls);
        CountDownLatch go = new CountDownLatch(1);
        CompletableFuture<Boolean> a = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return worker.runOnce();
        });
        CompletableFuture<Boolean> b = CompletableFuture.supplyAsync(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return worker.runOnce();
        });
        go.countDown();
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
        // 한쪽이 모델을 기다리는 동안 다른 쪽은 꺼낼 작업이 없어 바로 돌아온다 (FOR UPDATE SKIP LOCKED + LEASED).
        // anyOf: 둘 중 먼저 끝난 쪽을 기다린다 — 모델을 붙잡은 쪽은 release 전에는 끝나지 않으므로 먼저 끝난 쪽이 빈손이다
        assertThat(CompletableFuture.anyOf(a, b).get(20, TimeUnit.SECONDS)).isEqualTo(false);
        CompletableFuture<Boolean> busy = a.isDone() ? b : a;
        assertThat(busy.isDone()).isFalse();
        release.countDown();
        assertThat(busy.get(20, TimeUnit.SECONDS)).isTrue();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(validation(id).get("status").asText()).isEqualTo("PASS");
        assertThat(count("select count(*) from validation_run where validation_id = " + id)).isEqualTo(1);
        assertThat(count("select count(*) from llm_usage where request_key = '" + usageKey(id, 1) + "'")).isEqualTo(1);
    }

    @Test
    void twoRecoveriesOfTheSameExpiredLease_closeItOnce() throws Exception {
        long id = accept(passableTask("A"));
        JobLease lease = repository.lease(120).orElseThrow();
        api.jdbc.sql("update validation_job set lease_until = now() - interval '1 second' where id = :j")
                .param("j", lease.jobId()).update();
        CountDownLatch go = new CountDownLatch(1);
        List<CompletableFuture<Boolean>> both = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            both.add(CompletableFuture.supplyAsync(() -> {
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return worker.recoverExpired();
            }));
        }
        go.countDown();
        for (CompletableFuture<Boolean> f : both) {
            f.get(20, TimeUnit.SECONDS);
        }
        JsonNode v = validation(id);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("validation_lease_expired");
        // 오류 항목·실행 종결이 한 번만 남는다
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isEqualTo(1);
        assertThat(one("select status from validation_run where validation_id = " + id)).isEqualTo("ERROR");
        assertThat(one("select status from validation_job where validation_id = " + id)).isEqualTo("FAILED");
        assertThat(worker.recoverExpired()).isFalse();
    }

    // ---- 취소 ----

    @Test
    void cancellingTheTaskWhileTheModelRuns_discardsTheLateResult_butKeepsItsCost() throws Exception {
        long[] t = passableTask("A");
        long id = accept(t);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        analyzerHoldsUntil(entered, release, calls);
        CompletableFuture<Boolean> running = CompletableFuture.supplyAsync(() -> worker.runOnce());
        assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();

        // 모델이 도는 동안 담당자가 작업을 취소한다 — 워커는 DB 잠금을 쥐고 있지 않아 취소가 기다리지 않는다
        HttpResponse<String> cancel = api.post(tasksPath() + "/" + t[0] + "/state-events",
                "{\"event\":\"CANCEL\",\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(200);
        assertThat(one("select status from validation where id = " + id)).isEqualTo("STALE");
        assertThat(one("select status from validation_run where validation_id = " + id)).isEqualTo("DISCARDED");

        release.countDown();
        assertThat(running.get(20, TimeUnit.SECONDS)).isTrue();
        // 늦게 온 PASS 는 버려지고 작업은 취소 그대로. 다만 모델을 부른 비용은 그 실행에 남는다
        assertThat(one("select status from validation where id = " + id)).isEqualTo("STALE");
        assertThat(one("select status from task where id = " + t[0])).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isZero();
        assertThat(one("select usage::text from validation_run where validation_id = " + id)).contains(usageKey(id, 1));
        assertThat(count("select count(*) from llm_usage where request_key = '" + usageKey(id, 1) + "'")).isEqualTo(1);
    }

    // ---- 롤백 실패 ----

    @Test
    void whenSavingTheResultAndRecordingTheFailureBothFail_leaseRecoveryClosesIt_andRetryFinishesIt() throws Exception {
        long[] t = passableTask("A");
        long id = accept(t);
        doAnswer(inv -> aiResponse(inv.getArgument(0))).when(analyzer).analyze(any());
        // DB 장애: 결과 저장(검증 종결)과 실패 기록(역시 검증 종결) 모두 실패한다
        doThrow(new IllegalStateException("DB 장애 (시험용)")).when(repository).finish(anyLong(), any(), any());
        assertThatThrownBy(() -> worker.runOnce()).isInstanceOf(IllegalStateException.class);

        // 두 트랜잭션이 모두 롤백돼 검증·실행은 RUNNING, 작업은 LEASED 로 남는다 (11-validation-rollback §5 첫 줄)
        assertThat(one("select status from validation where id = " + id)).isEqualTo("RUNNING");
        assertThat(one("select status from validation_run where validation_id = " + id)).isEqualTo("RUNNING");
        assertThat(one("select status from validation_job where validation_id = " + id)).isEqualTo("LEASED");
        assertThat(count("select count(*) from validation_finding where validation_id = " + id)).isZero();
        assertThat(worker.runOnce()).isFalse();                 // 아직 리스가 살아 있어 다른 워커가 집지 않는다

        // DB 가 돌아오고 리스가 지나면 주기 회수가 리스 만료(RETRY)로 닫는다 — 원인이 DB 장애여도 코드는 리스 만료
        doCallRealMethod().when(repository).finish(anyLong(), any(), any());
        api.jdbc.sql("update validation_job set lease_until = now() - interval '1 second' where validation_id = :v")
                .param("v", id).update();
        assertThat(worker.recoverExpired()).isTrue();
        JsonNode v = validation(id);
        assertThat(v.get("status").asText()).isEqualTo("ERROR");
        assertThat(v.get("error_code").asText()).isEqualTo("validation_lease_expired");
        assertThat(v.get("findings").get(0).get("resolution").asText()).isEqualTo("RETRY");
        // 작업은 그대로 READY — 실패가 작업 상태를 바꾸지 않는다
        assertThat(one("select status from task where id = " + t[0])).isEqualTo("READY");

        // 담당자가 다시 돌리면 끝까지 간다
        HttpResponse<String> retry = api.post("/api/projects/" + projectId + "/validations/" + id + "/retry",
                "{\"expected_attempt\":1}", member);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(202);
        assertThat(worker.runOnce()).isTrue();
        assertThat(validation(id).get("status").asText()).isEqualTo("PASS");
        assertThat(count("select count(*) from llm_usage where request_key = '" + usageKey(id, 2) + "'")).isEqualTo(1);
    }
}
