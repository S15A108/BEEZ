package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.McpTestClient;
import com.beez.backend.validation.repository.ValidationRepository;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import io.modelcontextprotocol.client.McpSyncClient;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/**
 * 작업·검증 활동 (S15P31A108-287, 계약 10-notification-activity-contract.md 6.2 "작업 유형 정하기"·"검증 완료 정하기").
 * 생성·계획 변경·상태 보고(START·RESUME·WAIT·COMPLETED·CANCEL)·검증 완료(접수 때 규칙·워커)·delta 검증 통과(S4)가 활동을
 * 한 건씩 남기는지, 같은 보고를 다시 보내면 더 남지 않는지를 실제 DB·필터 체인·워커 위에서 본다.
 * 설계 발행의 REVALIDATION_REQUIRED 알림은 ProposalPublicationImpactIntegrationTest 가 본다(한 건씩·재생 무기록·미배정 제외).
 *
 * <p>빈 대체 구성은 PublicationImpactIntegrationTests 와 같게 둔다 — 같은 Spring 컨텍스트를 함께 쓰도록.
 */
class TaskActivityIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ValidationWorker worker;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

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
        when(search.search(any(), eq(projectId), anyString(), isNull(), isNull(), eq(100))).thenAnswer(call -> {
            Long baseline = api.jdbc.sql("select current_baseline_id from project where id=:p")
                    .param("p", projectId).query(Long.class).single();
            return new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false);
        });
    }

    /**
     * 깨지면: 상태 보고가 타임라인에 안 남거나(AFTER_COMMIT 로그로 돌아감), 보고 이름과 실제 전이가 어긋난 유형이 남거나,
     * 진행 중 계획 변경(S2)이 재검증 대기 활동을 따로 한 건 더 남기거나, 멱등 재전송(START·COMPLETED·CANCEL)이 한 건 더 남긴다.
     */
    @Test
    void createReviseAndReports_recordOnceEach_andResendsRecordNothing() throws Exception {
        long[] a = createTask("A", api.implementsRef("API-01"));
        long pass = api.passValidation(a[0]);
        long started = lock(state(a[0], "START", a[1], a[2], pass, null), 200);
        lock(state(a[0], "START", a[1], started, pass, null), 200);                     // 멱등 재전송
        // 진행 중 계획 변경(S2) → WAITING(REVALIDATION). 활동은 계획 변경 한 건
        HttpResponse<String> revised = api.post(tasksPath() + "/" + a[0] + "/plan-revisions",
                revisionJson("A2", api.implementsRef("API-02"), 1, started), member);
        assertThat(revised.statusCode()).as(revised.body()).isEqualTo(201);
        long revisedLock = TaskApiSupport.longField(revised.body(), "lock_version");
        long rev2 = currentRevision(a[0]);
        long pass2 = api.passValidation(a[0]);
        long resumed = lock(state(a[0], "RESUME", rev2, revisedLock, pass2, null), 200);
        long waited = lock(state(a[0], "WAIT", rev2, resumed, null, "\"waiting_reason\":\"INPUT\""), 200);
        long resumedAgain = lock(state(a[0], "RESUME", rev2, waited, pass2, null), 200);
        String done = "\"summary\":\"끝\",\"delta\":[]";
        long completed = lock(state(a[0], "COMPLETED", rev2, resumedAgain, null, done), 200);
        lock(state(a[0], "COMPLETED", rev2, resumedAgain, null, done), 200);              // 멱등 재전송

        // 다른 작업: READY 에서 취소, 다시 보내도 한 건
        long[] b = createTask("B", api.implementsRef("API-02"));
        long cancelled = lock(state(b[0], "CANCEL", b[1], b[2], null, null), 200);
        lock(state(b[0], "CANCEL", b[1], cancelled, null, null), 200);

        String human = "|HUMAN|" + member + "|SUCCESS";
        String ta = "task:" + a[0];
        assertThat(activities(a[0])).containsExactly(
                "TASK_CREATED|" + ta + ":created|TASK|" + a[0] + human,
                "TASK_STARTED|" + ta + ":lock:" + started + ":START|TASK|" + a[0] + human,
                "TASK_PLAN_REVISED|" + ta + ":lock:" + revisedLock + ":PLAN_REVISED|TASK|" + a[0] + human,
                "TASK_RESUMED|" + ta + ":lock:" + resumed + ":RESUME|TASK|" + a[0] + human,
                "TASK_WAITING|" + ta + ":lock:" + waited + ":WAIT|TASK|" + a[0] + human,
                "TASK_RESUMED|" + ta + ":lock:" + resumedAgain + ":RESUME|TASK|" + a[0] + human,
                "TASK_COMPLETED|" + ta + ":lock:" + completed + ":COMPLETED|TASK|" + a[0] + human);
        String tb = "task:" + b[0];
        assertThat(activities(b[0])).containsExactly(
                "TASK_CREATED|" + tb + ":created|TASK|" + b[0] + human,
                "TASK_CANCELLED|" + tb + ":lock:" + cancelled + ":CANCEL|TASK|" + b[0] + human);
    }

    /**
     * 깨지면: delta 있는 완료 보고가 완료로 보이거나, 워커가 끝낸 검증이 사람 행위로 남거나, ERROR 뒤 재시도로 같은 검증이 다시 끝날 때
     * 키가 겹쳐 두 번째 완료가 조용히 빠지거나, S4 완료가 남지 않거나 완료 보고와 키가 겹친다.
     */
    @Test
    void deltaReport_workerFailureRetryAndPass_recordsSystemFinishesAndCompletion() throws Exception {
        provideCompleteApi01();
        long[] f = createTask("F", api.implementsRef("API-01"));
        long started = lock(state(f[0], "START", f[1], f[2], api.passValidation(f[0]), null), 200);
        String delta = "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"응답에 nickname 추가\"}]";
        HttpResponse<String> reported = state(f[0], "COMPLETED", f[1], started, null, delta);
        long reportedLock = lock(reported, 202);
        long deltaId = TaskApiSupport.longField(reported.body(), "delta_validation_id");
        lock(state(f[0], "COMPLETED", f[1], started, null, delta), 202);                  // 같은 보고 재전송

        // 시도 1: 분석 호출 실패 → ERROR. 작업은 WAITING(REVALIDATION) 그대로
        doThrow(new RuntimeException("down")).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(column("validation", "status", deltaId)).isEqualTo("ERROR");
        // 시도 2: 재시도 → PASS → 서버가 완료(S4)
        HttpResponse<String> retried = api.post("/api/projects/" + projectId + "/validations/" + deltaId + "/retry",
                "{\"expected_attempt\":1}", member);
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(202);
        doAnswer(inv -> aiResponse(inv.getArgument(0))).when(analyzer).analyze(any());
        assertThat(worker.runOnce()).isTrue();
        assertThat(column("validation", "status", deltaId)).isEqualTo("PASS");
        assertThat(column("task", "status", f[0])).isEqualTo("COMPLETED");
        long completedLock = Long.parseLong(column("task", "lock_version", f[0]));
        assertThat(completedLock).isGreaterThan(reportedLock);

        String tf = "task:" + f[0];
        String human = "|HUMAN|" + member + "|SUCCESS";
        String finished = "VALIDATION_FINISHED|validation:" + deltaId + ":finished:";
        assertThat(activities(f[0])).containsExactly(
                "TASK_CREATED|" + tf + ":created|TASK|" + f[0] + human,
                "TASK_STARTED|" + tf + ":lock:" + started + ":START|TASK|" + f[0] + human,
                "TASK_COMPLETION_REPORTED|" + tf + ":lock:" + reportedLock + ":COMPLETED|TASK|" + f[0] + human,
                finished + "1|VALIDATION|" + deltaId + "|SYSTEM|null|FAILURE",
                finished + "2|VALIDATION|" + deltaId + "|SYSTEM|null|SUCCESS",
                "TASK_COMPLETED|" + tf + ":lock:" + completedLock + ":COMPLETED|TASK|" + f[0] + "|SYSTEM|null|SUCCESS");
    }

    /** 깨지면: 요청 처리 안에서 규칙만으로 끝난 검증(VAL-A01 200)이 남지 않거나, 요청자가 아니라 시스템으로 남는다. */
    @Test
    void acceptDecidedByRules_recordsFinishByRequester() throws Exception {
        // CHANGES 참조인데 계약 변경 없음으로 선언 → 규칙만으로 NEEDS_INPUT (모델 호출 없음)
        long[] t = createTask("R", api.changesRef("API-01"));
        HttpResponse<String> res = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        long validationId = TaskApiSupport.longField(res.body(), "id");

        assertThat(activities(t[0])).containsExactly(
                "TASK_CREATED|task:" + t[0] + ":created|TASK|" + t[0] + "|HUMAN|" + member + "|SUCCESS",
                "VALIDATION_FINISHED|validation:" + validationId + ":finished:0|VALIDATION|" + validationId
                        + "|HUMAN|" + member + "|FAILURE");
    }

    /**
     * 깨지면: 에이전트가 MCP-12 로 보고한 전이가 사람이나 시스템 행위로 남거나, 어느 연결이 했는지(agent_session_id)가 빠진다.
     * MCP 경로는 도구 처리의 바깥 트랜잭션 안에서 보고하므로 커밋 직전 기록이 그 바깥 커밋에 묶인다 — REST 경로 시험으로는 보이지 않는다.
     */
    @Test
    void agentReportOverMcp_recordsAgentActorAndItsSession() throws Exception {
        String token = SecretTokens.generate();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(token)).param("id", api.agentTokenId()).update();
        long[] t = createTask("M", api.implementsRef("API-01"));
        long pass = api.passValidation(t[0]);

        McpSyncClient agent = McpTestClient.connect(env.getRequiredProperty("local.server.port", Integer.class), token);
        String session;
        long started;
        try {
            agent.initialize();
            session = McpTestClient.ok(json, McpTestClient.call(agent, "check_connection", Map.of()))
                    .get("agent_session_id").asString();
            started = McpTestClient.ok(json, McpTestClient.call(agent, "report_task_state", Map.of(
                    "task_id", String.valueOf(t[0]), "event", "START", "plan_revision_id", String.valueOf(t[1]),
                    "expected_lock_version", t[2], "validation_id", String.valueOf(pass), "idempotency_key", "k-mcp-start")))
                    .get("task").get("lock_version").asLong();
        } finally {
            agent.closeGracefully();                     // 종료 DELETE 를 기다려야 다음 시험의 truncate 와 교착하지 않는다
        }

        assertThat(api.jdbc.sql("""
                        select event_type, dedupe_key, actor_kind, agent_session_id
                          from activity_event where project_id = :p and task_id = :t and event_type = 'TASK_STARTED'
                        """)
                .param("p", projectId).param("t", t[0])
                .query((rs, n) -> String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list())
                .containsExactly("TASK_STARTED|task:" + t[0] + ":lock:" + started + ":START|AGENT|" + session);
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
        return new long[] {taskId, currentRevision(taskId), TaskApiSupport.longField(res.body(), "lock_version")};
    }

    private long currentRevision(long taskId) {
        return api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId).query(Long.class).single();
    }

    private String revisionJson(String title, String refsJson, int expectedRevisionNo, long expectedLockVersion) {
        String plan = api.planJson(title, refsJson, "[]").trim();
        return plan.substring(0, plan.lastIndexOf('}')) + ",\n  \"expected_revision_no\": " + expectedRevisionNo
                + ",\n  \"expected_lock_version\": " + expectedLockVersion + "\n}";
    }

    private HttpResponse<String> state(long taskId, String event, long revisionId, long lock, Long validationId, String extra)
            throws Exception {
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

    /** 응답 상태를 확인하고 응답의 lock_version 을 돌려준다 */
    private static long lock(HttpResponse<String> res, int status) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return TaskApiSupport.longField(res.body(), "lock_version");
    }

    private String column(String table, String column, long id) {
        return api.jdbc.sql("select " + column + "::text from " + table + " where id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    /** 그 작업의 활동(보조 참조 task_id 기준), id 순. "유형|키|대상 종류|대상 id|행위자 종류|행위 계정|결과" */
    private List<String> activities(long taskId) {
        return api.jdbc.sql("""
                        select event_type, dedupe_key, ref_kind, ref_id, actor_kind, actor_id, result
                          from activity_event where project_id = :p and task_id = :t order by id
                        """)
                .param("p", projectId).param("t", taskId)
                .query((rs, n) -> String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), String.valueOf(rs.getObject(6)), rs.getString(7)))
                .list();
    }

    /** PASS 하려면 근거 검사(#184)를 통과할 만큼 갖춘 API 계약이 승인돼 있어야 한다 (PublicationImpactIntegrationTests 와 같은 준비) */
    private void provideCompleteApi01() {
        Long version = api.jdbc.sql("""
                insert into design_item_version (design_item_id, version_no, status, title, schema_version,
                    content, content_hash, origin, approved_at)
                values (:item, 2, 'APPROVED', 'API-01 complete', 1, cast(:content as jsonb),
                    'complete-api-01', 'HUMAN', now()) returning id
                """).param("item", api.designItemId("API-01"))
                .param("content", "{\"request_fields\":[],\"responses\":[{\"fields\":[]}]}")
                .query(Long.class).single();
        api.publish(2, Map.of("API-01", version, "API-02", api.currentVersionId("API-02")));
    }

    /** 발견 없는 정상 응답. 사용량 키는 요청의 검증·시도 번호로 만든다 */
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
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("result", result);
        response.put("origin", "MODEL");
        response.put("model", "test-model");
        response.put("usage", List.of(Map.of(
                "request_key", "llm-validate-" + request.getOrDefault("validation_id", "x")
                        + "-attempt-" + request.getOrDefault("attempt", 1) + "-call-1",
                "feature", "TASK_VALIDATION", "model", "test-model", "failed", false,
                "tokens_known", true, "latency_ms", 10, "input_tokens", 10, "output_tokens", 5)));
        return response;
    }
}
