package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beez.backend.changeproposal.dto.PublicationDtos.ChangedItem;
import com.beez.backend.changeproposal.dto.PublicationDtos.ImpactRequest;
import com.beez.backend.changeproposal.dto.PublicationDtos.ImpactResult;
import com.beez.backend.changeproposal.dto.PublicationDtos.StaleValidation;
import com.beez.backend.changeproposal.dto.PublicationDtos.WaitingTask;
import com.beez.backend.changeproposal.port.ValidationImpactPort;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.repository.JobLease;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.service.AnalysisResultApplier;
import com.beez.backend.validation.service.FakeValidationAnalyzer;
import com.beez.backend.validation.service.PublicationImpactAdapter;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설계 발행 수신·무효화·재검증 연결 (S15P31A108-163) + 완료 delta 의 DELTA 검증 완료 전이 (S4).
 * 발행 트랜잭션이 부르는 포트({@link ValidationImpactPort})를 같은 조건(트랜잭션 안)으로 직접 부른다 — 발행 자체(승인·기준 생성)는
 * 변경 제안 쪽 시험이 맡고, 여기서는 그 접점이 검증·작업에 미치는 영향만 본다. 기준 행은 TaskApiSupport.publish 로 만든다.
 */
class PublicationImpactIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    ValidationImpactPort port;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ValidationWorker worker;

    @Autowired
    AnalysisResultApplier applier;

    /** 실제 저장소 그대로. STALE 대상 계산이 한 번만 도는지 세는 데만 쓴다 */
    @MockitoSpyBean
    ValidationRepository repository;

    @MockitoBean
    ValidationAnalyzer analyzer;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        // 포트의 실제 구현이 붙어 있어야 한다 (기본 no-op 이 아니라)
        assertThat(port).isInstanceOf(PublicationImpactAdapter.class);
    }

    // ---- 도우미 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 작업 생성 → {taskId, revisionId, lockVersion} */
    private long[] createTask(String title, String refsJson) throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson(title, refsJson, "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        return new long[] {taskId, revisionId, TaskApiSupport.longField(res.body(), "lock_version")};
    }

    /** 검증 접수 (VAL-A01) → 검증 번호 */
    private long accept(long[] t, int expectedStatus) throws Exception {
        HttpResponse<String> res = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expectedStatus);
        return TaskApiSupport.longField(res.body(), "id");
    }

    private HttpResponse<String> state(long taskId, String event, long revisionId, long lock, Long validationId, String extra) throws Exception {
        StringBuilder sb = new StringBuilder("{\"event\":\"").append(event).append("\",\"plan_revision_id\":\"").append(revisionId)
                .append("\",\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":\"").append(validationId).append("\"");
        }
        if (extra != null) {
            sb.append(",").append(extra);
        }
        return api.post(tasksPath() + "/" + taskId + "/state-events", sb.append("}").toString(), member);
    }

    /** PASS 검증(규칙으로 끝나는 READS 계획이면 접수로, 아니면 SQL 로)으로 START 까지 */
    private long start(long[] t) throws Exception {
        long pass = api.passValidation(t[0]);
        HttpResponse<String> res = state(t[0], "START", t[1], t[2], pass, null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return TaskApiSupport.longField(res.body(), "lock_version");
    }

    private String column(String table, String column, long id) {
        return api.jdbc.sql("select " + column + "::text from " + table + " where id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private Map<String, Object> task(long taskId) {
        return api.jdbc.sql("select status, waiting_reason, lock_version, completed_at from task where id = :t").param("t", taskId)
                .query().singleRow();
    }

    /** API-01 을 v2 로 발행한 것처럼 기준 행을 만들고, 포트에 넘길 요청을 돌려준다 (현재 포인터 교체는 publish 가 같이 한다) */
    private ImpactRequest publishApi01() {
        long item = api.designItemId("API-01");
        long before = api.currentVersionId("API-01");
        long previousBaseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        long after = api.newVersion("API-01", 2);
        long baseline = api.publish(2, Map.of("API-01", after, "API-02", api.currentVersionId("API-02")));
        return new ImpactRequest(baseline, previousBaseline, 1L, 1, "hash", List.of(new ChangedItem(item, before, after, "UPDATE")), "발행");
    }

    private ImpactResult apply(ImpactRequest request) {
        return new TransactionTemplate(transactions).execute(status -> port.applyPublication(CallerContext.human(leader), projectId, request));
    }

    private ImpactResult preview(ImpactRequest request) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        return tx.execute(status -> port.previewPublication(projectId, request));
    }

    private static List<String> ids(List<StaleValidation> stale) {
        List<String> out = new ArrayList<>();
        for (StaleValidation s : stale) {
            out.add(s.validationId());
        }
        return out;
    }

    private static List<String> taskIds(List<WaitingTask> waiting) {
        List<String> out = new ArrayList<>();
        for (WaitingTask w : waiting) {
            out.add(w.taskId());
        }
        return out;
    }

    private static Map<String, Object> aiResponse(Map<String, Object> request, List<Map<String, Object>> findings) {
        return aiResponse(request, findings, "test-model");
    }

    private static Map<String, Object> aiResponse(Map<String, Object> request, List<Map<String, Object>> findings, String model) {
        List<String> checked = new ArrayList<>();
        for (Object c : (List<?>) request.get("checks")) {
            checked.add(String.valueOf(((Map<?, ?>) c).get("key")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("analysis_status", "COMPLETE");
        result.put("checked_keys", checked);
        result.put("unassessed", List.of());
        result.put("findings", findings);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", model);
        response.put("usage", List.of(Map.of("request_key", "llm-validate-x-attempt-1-call-1", "input_tokens", 10, "output_tokens", 5)));
        return response;
    }

    private static Map<String, Object> blockingMismatch(long item, long version) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("check_key", "design-" + item);
        f.put("kind", "MISMATCH");
        f.put("severity", "BLOCKING");
        f.put("message", "응답 필드가 설계와 다릅니다");
        f.put("design_item_id", String.valueOf(item));
        f.put("design_item_version_id", String.valueOf(version));
        f.put("sources", List.of());
        f.put("evidence_ids", List.of());
        f.put("suggested_action", "설계를 고치거나 되돌리세요");
        return f;
    }

    // ---- S1: 발행 → 검증 STALE, IN_PROGRESS 만 WAITING ----

    @Test
    void publication_stalesValidationsOnOldVersion_andHoldsOnlyInProgressTasks() throws Exception {
        // A: API-01 을 IMPLEMENTS, 시작함 (IN_PROGRESS) — 검증 STALE + 작업 WAITING(REVALIDATION)
        long[] a = createTask("A", api.implementsRef("API-01"));
        long lockA = start(a);
        long passA = api.jdbc.sql("select max(id) from validation where task_id = :t").param("t", a[0]).query(Long.class).single();
        // B: API-01 을 READS, PASS 가 있고 아직 READY — 검증만 STALE, 상태 그대로
        // (A 가 같은 항목을 보는 열린 작업이라 접수하면 의미 분석으로 가므로, PASS 는 SQL 로 만든다)
        long[] b = createTask("B", api.readsRef("API-01"));
        long passB = api.passValidation(b[0]);
        // C: API-02 만 참조 — 무관
        long[] c = createTask("C", api.implementsRef("API-02"));
        long passC = accept(c, 202);
        // D: API-01 을 IMPLEMENTS, 의미 분석 대기 중(RUNNING, 큐에 있음) — STALE + job 취소 + 실행 DISCARDED
        long[] d = createTask("D", api.implementsRef("API-01"));
        long runningD = accept(d, 202);

        ImpactRequest request = publishApi01();

        // 미리보기는 같은 계산을 쓰기 없이 한다
        ImpactResult previewed = preview(request);
        assertThat(ids(previewed.staleValidations())).containsExactly(String.valueOf(passA), String.valueOf(passB), String.valueOf(runningD));
        assertThat(taskIds(previewed.waitingTasks())).containsExactly(String.valueOf(a[0]));
        assertThat(column("validation", "status", passA)).isEqualTo("PASS");
        assertThat(task(a[0]).get("status")).isEqualTo("IN_PROGRESS");

        org.mockito.Mockito.clearInvocations(repository);
        ImpactResult applied = apply(request);
        // STALE 대상은 한 번만 계산하고 무효화·응답이 그 결과를 같이 쓴다
        verify(repository, times(1)).usableValidationsReferencingAtOrBelow(anyLong(), anyList(), anyList());
        assertThat(ids(applied.staleValidations())).containsExactly(String.valueOf(passA), String.valueOf(passB), String.valueOf(runningD));
        assertThat(applied.staleValidations().get(0).taskTitle()).isEqualTo("A");
        assertThat(taskIds(applied.waitingTasks())).containsExactly(String.valueOf(a[0]));
        assertThat(applied.waitingTasks().get(0).assigneeMemberId()).isEqualTo(String.valueOf(api.memberId("member@test")));

        for (long v : List.of(passA, passB, runningD)) {
            assertThat(column("validation", "status", v)).isEqualTo("STALE");
            assertThat(column("validation", "stale_reason", v)).isEqualTo(PublicationImpactAdapter.STALE_REASON);
        }
        assertThat(column("validation", "status", passC)).isEqualTo("RUNNING");             // 무관한 항목은 그대로
        // 작업: IN_PROGRESS 만 WAITING(REVALIDATION), 점유 유지. READY 는 그대로
        Map<String, Object> taskA = task(a[0]);
        assertThat(taskA.get("status")).isEqualTo("WAITING");
        assertThat(taskA.get("waiting_reason")).isEqualTo("REVALIDATION");
        assertThat(((Number) taskA.get("lock_version")).longValue()).isGreaterThan(lockA);
        assertThat(task(b[0]).get("status")).isEqualTo("READY");
        assertThat(task(c[0]).get("status")).isEqualTo("READY");
        // 대기 중이던 D 의 job 은 취소되고 실행은 닫힌다 → 워커가 꺼낼 것이 없다
        assertThat(api.jdbc.sql("select status from validation_job where validation_id = :v").param("v", runningD).query(String.class).single())
                .isEqualTo("CANCELLED");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id = :v").param("v", runningD).query(String.class).single())
                .isEqualTo("DISCARDED");
        AtomicBoolean called = new AtomicBoolean(false);
        doAnswer(inv -> {
            called.set(true);
            return aiResponse(inv.getArgument(0), List.of());
        }).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();                                             // C 만 남아 있다
        assertThat(called.get()).isTrue();
        assertThat(column("validation", "status", passC)).isEqualTo("PASS");
        assertThat(worker.runOnce()).isFalse();

        // 낡은 PASS 로는 B 를 시작할 수 없다 (G1)
        HttpResponse<String> startB = state(b[0], "START", b[1], b[2], passB, null);
        assertThat(startB.statusCode()).as(startB.body()).isEqualTo(409);

        // 같은 발행을 다시 적용해도 더 바뀌는 것이 없다 (멱등)
        long lockAfter = ((Number) task(a[0]).get("lock_version")).longValue();
        ImpactResult again = apply(request);
        assertThat(again.staleValidations()).isEmpty();
        assertThat(again.waitingTasks()).isEmpty();
        assertThat(((Number) task(a[0]).get("lock_version")).longValue()).isEqualTo(lockAfter);
    }

    @Test
    void documentOnlyPublication_changesNothing() throws Exception {
        long[] a = createTask("A", api.implementsRef("API-01"));
        start(a);
        long previousBaseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        ImpactRequest request = new ImpactRequest(previousBaseline + 1, previousBaseline, 1L, 1, "hash", List.of(), "문서만");
        assertThat(apply(request)).isEqualTo(ImpactResult.empty());
        assertThat(task(a[0]).get("status")).isEqualTo("IN_PROGRESS");
    }

    // ---- S3·S4 + 발행 뒤 DELTA 재접수 ----

    @Test
    void deltaValidationPass_completesTask_andBlockingCandidateKeepsItWaiting() throws Exception {
        // F: delta 완료 보고 → DELTA 검증 RUNNING → 워커가 PASS → 서버가 COMPLETED (S4)
        long[] f = createTask("F", api.implementsRef("API-01"));
        long lockF = start(f);
        HttpResponse<String> done = state(f[0], "COMPLETED", f[1], lockF, null,
                "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"응답에 nickname 추가\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long deltaF = TaskApiSupport.longField(done.body(), "delta_validation_id");
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        doAnswer(inv -> {
            sent.set(inv.getArgument(0));
            return aiResponse(inv.getArgument(0), List.of());
        }).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        // 완료 보고의 delta 가 분석 입력에 들어가고, 그 검사(completion-delta)가 검사 목록에 있다
        assertThat(sent.get().get("completion_delta")).isEqualTo(List.of(Map.of("kind", "ADDED_FIELD", "text", "응답에 nickname 추가")));
        List<String> checkKeys = new ArrayList<>();
        for (Object c : (List<?>) sent.get().get("checks")) {
            checkKeys.add(String.valueOf(((Map<?, ?>) c).get("key")));
        }
        assertThat(checkKeys).contains("completion-delta");
        assertThat(column("validation", "status", deltaF)).isEqualTo("PASS");
        Map<String, Object> taskF = task(f[0]);
        assertThat(taskF.get("status")).isEqualTo("COMPLETED");
        assertThat(taskF.get("completed_at")).isNotNull();

        // G: 같은 흐름인데 AI 가 막는 후보를 내면 NEEDS_REVIEW → 작업은 WAITING(REVALIDATION) 그대로
        long[] g = createTask("G", api.implementsRef("API-01"));
        long lockG = start(g);
        HttpResponse<String> doneG = state(g[0], "COMPLETED", g[1], lockG, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(doneG.statusCode()).as(doneG.body()).isEqualTo(202);
        long deltaG = TaskApiSupport.longField(doneG.body(), "delta_validation_id");
        Map<String, Object> blocking = blockingMismatch(api.designItemId("API-01"), api.currentVersionId("API-01"));
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of(blocking))).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(column("validation", "status", deltaG)).isEqualTo("NEEDS_REVIEW");
        assertThat(task(g[0]).get("status")).isEqualTo("WAITING");
        assertThat(task(g[0]).get("waiting_reason")).isEqualTo("REVALIDATION");
        // WAITING + COMPLETED 재전송은 멱등(202), 새 DELTA 를 만들지 않는다
        HttpResponse<String> resend = state(g[0], "COMPLETED", g[1], lockG, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(resend.statusCode()).as(resend.body()).isEqualTo(202);
        assertThat(api.jdbc.sql("select count(*) from validation where task_id = :t and trigger = 'DELTA'").param("t", g[0])
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void publicationDuringDeltaValidation_stalesIt_andAcceptsNewDeltaAgainstNewBaseline() throws Exception {
        long[] e = createTask("E", api.implementsRef("API-01"));
        long lockE = start(e);
        HttpResponse<String> done = state(e[0], "COMPLETED", e[1], lockE, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long oldDelta = TaskApiSupport.longField(done.body(), "delta_validation_id");
        long lockWaiting = ((Number) task(e[0]).get("lock_version")).longValue();

        ImpactRequest request = publishApi01();
        ImpactResult applied = apply(request);

        // 기다리던 DELTA 는 STALE, 작업은 이미 WAITING(REVALIDATION) 이라 그대로 (목록에도 없다)
        assertThat(column("validation", "status", oldDelta)).isEqualTo("STALE");
        assertThat(ids(applied.staleValidations())).contains(String.valueOf(oldDelta));
        assertThat(applied.waitingTasks()).isEmpty();
        Map<String, Object> taskE = task(e[0]);
        assertThat(taskE.get("status")).isEqualTo("WAITING");
        assertThat(taskE.get("waiting_reason")).isEqualTo("REVALIDATION");
        assertThat(((Number) taskE.get("lock_version")).longValue()).isEqualTo(lockWaiting);

        // 서버가 새 기준으로 새 DELTA 를 접수했다. 계획은 아직 v1 을 참조하므로 규칙이 바로 NEEDS_INPUT(STALE_REF) — 담당자가 계획을 올릴 차례
        Map<String, Object> latest = api.jdbc.sql("""
                        select id, status, baseline_id from validation
                         where task_id = :t and trigger = 'DELTA' order by id desc limit 1
                        """).param("t", e[0]).query().singleRow();
        long newDelta = ((Number) latest.get("id")).longValue();
        assertThat(newDelta).isGreaterThan(oldDelta);
        assertThat(latest.get("status")).isEqualTo("NEEDS_INPUT");
        assertThat(((Number) latest.get("baseline_id")).longValue()).isEqualTo(request.baselineId());
        assertThat(api.jdbc.sql("select kind from validation_finding where validation_id = :v").param("v", newDelta)
                .query(String.class).list()).containsExactly("STALE_REF");
        // 조회의 delta_validation_id 는 새 DELTA 를 가리킨다
        HttpResponse<String> detail = api.get(tasksPath() + "/" + e[0], member);
        assertThat(TaskApiSupport.longField(detail.body(), "delta_validation_id")).isEqualTo(newDelta);
        // 옛 DELTA 의 늦은 결과는 반영되지 않는다 (큐도 취소됨)
        assertThat(api.jdbc.sql("select status from validation_job where validation_id = :v").param("v", oldDelta).query(String.class).single())
                .isEqualTo("CANCELLED");
        assertThat(worker.runOnce()).isFalse();
    }

    @Test
    void publicationWhileInProgress_doesNotReacceptAnOldDelta() throws Exception {
        // H: delta 완료 → DELTA NEEDS_REVIEW → RESUME 해서 다시 작업 중. 이때 발행되면 WAITING 으로 내리되, 옛 DELTA 를 다시 접수하지 않는다
        long[] h = createTask("H", api.implementsRef("API-01"));
        long lockH = start(h);
        HttpResponse<String> done = state(h[0], "COMPLETED", h[1], lockH, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        Map<String, Object> blocking = blockingMismatch(api.designItemId("API-01"), api.currentVersionId("API-01"));
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of(blocking))).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        long startPass = api.jdbc.sql("select started_validation_id from task where id = :t").param("t", h[0]).query(Long.class).single();
        long lockWaiting = ((Number) task(h[0]).get("lock_version")).longValue();
        HttpResponse<String> resumed = state(h[0], "RESUME", h[1], lockWaiting, startPass, null);   // ① 코드를 되돌리기로 함
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(task(h[0]).get("status")).isEqualTo("IN_PROGRESS");

        ImpactResult applied = apply(publishApi01());
        assertThat(taskIds(applied.waitingTasks())).containsExactly(String.valueOf(h[0]));
        assertThat(task(h[0]).get("status")).isEqualTo("WAITING");
        assertThat(api.jdbc.sql("select count(*) from validation where task_id = :t and trigger = 'DELTA'").param("t", h[0])
                .query(Integer.class).single()).isEqualTo(1);                               // 새 DELTA 없음
        JsonNode detail = JSON.readTree(api.get(tasksPath() + "/" + h[0], member).body());
        assertThat(detail.get("validation_status").asText()).isEqualTo("STALE");
    }

    @Test
    void fakeAnalyzerDeltaPass_doesNotCompleteTask_whenFakeAnalyzerIsOff() throws Exception {
        // 시작 관문 G1 과 같은 규칙: 가짜 분석기를 끈 서버에서 가짜 분석기가 낸 PASS 는 완료로 쓰지 않는다
        long[] k = createTask("K", api.implementsRef("API-01"));
        long lockK = start(k);
        HttpResponse<String> done = state(k[0], "COMPLETED", k[1], lockK, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long deltaK = TaskApiSupport.longField(done.body(), "delta_validation_id");
        doAnswer(inv -> aiResponse(inv.getArgument(0), List.of(), FakeValidationAnalyzer.MODEL)).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(column("validation", "status", deltaK)).isEqualTo("PASS");
        assertThat(task(k[0]).get("status")).isEqualTo("WAITING");
        assertThat(task(k[0]).get("waiting_reason")).isEqualTo("REVALIDATION");
    }

    @Test
    void resume_invalidatesPendingDelta_soItsLatePassCannotCompleteTheTaskLater() throws Exception {
        // 리뷰 순서: delta 완료 보고 → DELTA RUNNING 인 채 시작 PASS 로 RESUME → 담당자 인계로 다시 WAITING(REVALIDATION) → 옛 DELTA 가 뒤늦게 PASS
        long[] r = createTask("R", api.implementsRef("API-01"));
        long lockR = start(r);
        long startPass = api.jdbc.sql("select started_validation_id from task where id = :t").param("t", r[0]).query(Long.class).single();
        HttpResponse<String> done = state(r[0], "COMPLETED", r[1], lockR, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long delta = TaskApiSupport.longField(done.body(), "delta_validation_id");
        JobLease lease = repository.lease(120).orElseThrow();                       // 워커가 DELTA 를 꺼내 AI 를 기다리는 중
        assertThat(lease.validationId()).isEqualTo(delta);

        // ① 코드를 되돌리기로 하고 RESUME → 기다리던 DELTA 는 STALE, 큐도 취소
        long lockWaiting = TaskApiSupport.longField(done.body(), "lock_version");
        HttpResponse<String> resumed = state(r[0], "RESUME", r[1], lockWaiting, startPass, null);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(column("validation", "status", delta)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", delta)).isEqualTo("TASK_RESUMED");
        assertThat(api.jdbc.sql("select status from validation_job where validation_id = :v").param("v", delta).query(String.class).single())
                .isEqualTo("CANCELLED");

        // 담당자 인계 → 다시 WAITING(REVALIDATION)
        long lockResumed = TaskApiSupport.longField(resumed.body(), "lock_version");
        HttpResponse<String> handover = api.patch(tasksPath() + "/" + r[0] + "/assignee",
                "{\"assignee_member_id\": \"" + api.memberId("leader@test") + "\", \"expected_lock_version\": " + lockResumed + "}", leader);
        assertThat(handover.statusCode()).as(handover.body()).isEqualTo(200);
        assertThat(task(r[0]).get("status")).isEqualTo("WAITING");
        assertThat(task(r[0]).get("waiting_reason")).isEqualTo("REVALIDATION");

        // 옛 DELTA 의 늦은 PASS 가 도착해도 버려지고 작업은 완료되지 않는다
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("key", "completion-delta");
        request.put("checks", List.of(check));
        applier.applySuccess(lease, request, aiResponse(request, List.of()), 0);
        assertThat(column("validation", "status", delta)).isEqualTo("STALE");
        assertThat(task(r[0]).get("status")).isEqualTo("WAITING");
        assertThat(task(r[0]).get("completed_at")).isNull();
        // 조회에도 옛 DELTA 가 "기다리는 검증" 으로 나오지 않는다 (가장 최근 DELTA 가 STALE 이면 비운다)
        JsonNode detail = JSON.readTree(api.get(tasksPath() + "/" + r[0], leader).body());
        assertThat(detail.has("delta_validation_id")).isFalse();
        assertThat(detail.get("validation_status").asText()).isEqualTo("STALE");
        // 목록에서도 같다 (묶음 조회가 같은 기준)
        JsonNode list = JSON.readTree(api.get(tasksPath() + "?status=WAITING", leader).body());
        for (JsonNode item : list.get("items")) {
            if (item.get("id").asLong() == r[0]) {
                assertThat(item.has("delta_validation_id")).isFalse();
            }
        }
    }

    @Test
    void resumeAfterErrorDelta_endsThatWait_soSameReportAfterHandoverIsNotAResend() throws Exception {
        // DELTA 가 ERROR 로 끝남 → 시작 PASS 로 RESUME → 인계로 다시 WAITING(REVALIDATION) → 같은 COMPLETED 재전송
        long[] e = createTask("E2", api.implementsRef("API-01"));
        long lockE = start(e);
        long startPass = api.jdbc.sql("select started_validation_id from task where id = :t").param("t", e[0]).query(Long.class).single();
        String deltaJson = "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]";
        HttpResponse<String> done = state(e[0], "COMPLETED", e[1], lockE, null, deltaJson);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long delta = TaskApiSupport.longField(done.body(), "delta_validation_id");
        doAnswer(inv -> {
            throw new ValidationAnalyzer.AnalyzerException("ai_unavailable", "AI 서버 오류 502", null);
        }).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(column("validation", "status", delta)).isEqualTo("ERROR");

        HttpResponse<String> resumed = state(e[0], "RESUME", e[1], TaskApiSupport.longField(done.body(), "lock_version"), startPass, null);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        // RESUME 으로 그 완료 대기는 끝났다 — ERROR 였던 DELTA 도 STALE(TASK_RESUMED). 오류 이력(실행·발견 항목)은 남는다
        assertThat(column("validation", "status", delta)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", delta)).isEqualTo("TASK_RESUMED");
        long errorRun = api.jdbc.sql("select id from validation_run where validation_id = :v").param("v", delta).query(Long.class).single();
        assertThat(column("validation_run", "status", errorRun)).isEqualTo("ERROR");

        HttpResponse<String> handover = api.patch(tasksPath() + "/" + e[0] + "/assignee",
                "{\"assignee_member_id\": \"" + api.memberId("leader@test") + "\", \"expected_lock_version\": "
                        + TaskApiSupport.longField(resumed.body(), "lock_version") + "}", leader);
        assertThat(handover.statusCode()).as(handover.body()).isEqualTo(200);
        assertThat(task(e[0]).get("status")).isEqualTo("WAITING");
        // 조회에 옛 ERROR DELTA 가 기다리는 검증으로 나오지 않고, 같은 보고를 다시 보내도 재전송(202)이 아니라 전이 오류(400)
        JsonNode detail = JSON.readTree(api.get(tasksPath() + "/" + e[0], leader).body());
        assertThat(detail.has("delta_validation_id")).isFalse();
        HttpResponse<String> again = api.post(tasksPath() + "/" + e[0] + "/state-events",
                "{\"event\":\"COMPLETED\",\"plan_revision_id\":\"" + e[1] + "\",\"expected_lock_version\":"
                        + TaskApiSupport.longField(handover.body(), "lock_version") + "," + deltaJson + "}", leader);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(400);
        assertThat(api.jdbc.sql("select count(*) from task_completion_report where task_id = :t").param("t", e[0]).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    void publicationTouchingTwoItemsOfOneWaitingTask_reacceptsDeltaOnce() throws Exception {
        String bothRefs = """
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "IMPLEMENTS"},
                 {"design_item_id": "%d", "design_item_version_id": "%d", "relation": "IMPLEMENTS"}]
                """.formatted(api.designItemId("API-01"), api.currentVersionId("API-01"), api.designItemId("API-02"), api.currentVersionId("API-02"));
        long[] m = createTask("M", bothRefs);
        long lockM = start(m);
        HttpResponse<String> done = state(m[0], "COMPLETED", m[1], lockM, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long oldDelta = TaskApiSupport.longField(done.body(), "delta_validation_id");

        // 두 항목이 한 발행에서 같이 바뀐다
        long item1 = api.designItemId("API-01");
        long item2 = api.designItemId("API-02");
        long before1 = api.currentVersionId("API-01");
        long before2 = api.currentVersionId("API-02");
        long previousBaseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single();
        long after1 = api.newVersion("API-01", 2);
        long after2 = api.newVersion("API-02", 2);
        long baseline = api.publish(2, Map.of("API-01", after1, "API-02", after2));
        ImpactResult applied = apply(new ImpactRequest(baseline, previousBaseline, 1L, 1, "hash",
                List.of(new ChangedItem(item1, before1, after1, "UPDATE"), new ChangedItem(item2, before2, after2, "UPDATE")), "발행"));

        assertThat(ids(applied.staleValidations())).containsOnlyOnce(String.valueOf(oldDelta));   // 두 항목에 걸려도 한 번
        assertThat(api.jdbc.sql("select count(*) from validation where task_id = :t and trigger = 'DELTA'").param("t", m[0])
                .query(Integer.class).single()).isEqualTo(2);                                      // 옛 것 + 새 것 하나
        assertThat(api.jdbc.sql("select count(*) from validation_finding where validation_id = (select max(id) from validation where task_id = :t)")
                .param("t", m[0]).query(Integer.class).single()).isEqualTo(2);                     // 항목마다 STALE_REF 하나씩
    }
}
