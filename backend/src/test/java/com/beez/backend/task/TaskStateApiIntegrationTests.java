package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * S15P31A108-158 상태 전이·시작 게이트. 지난주 DB 에서 SQL 로 검증한 시나리오(동시 START, 멱등, 무효화)를 HTTP 로 옮겼다.
 * 검증 실행 기능(-59)은 아직 없어 PASS 검증 행은 SQL 로 만든다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class TaskStateApiIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d")
                    .asCompatibleSubstituteFor("postgres"));

    @Autowired
    Environment env;

    @Autowired
    DataSource dataSource;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(Integer.parseInt(env.getProperty("local.server.port")), dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 작업 생성 → {taskId, revisionId, lockVersion} */
    private long[] createTask(String title, String refsJson, String depsJson, Long account) throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson(title, refsJson, depsJson), account);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        long lock = TaskApiSupport.longField(res.body(), "lock_version");
        return new long[]{taskId, revisionId, lock};
    }

    /** 현재 리비전에 대한 PASS 검증을 만들고, 계획의 참조를 검증 참조로 복사한다 (워커가 할 일을 SQL 로) */
    private long passValidation(long taskId, long revisionId) {
        Long baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        values (:t, :r, :b, 'PASS', 'AGENT', 'snap-' || :r, 'r1')
                        """).param("t", taskId).param("r", revisionId).param("b", baselineId).update();
        Long validationId = api.jdbc.sql("select max(id) from validation where task_id = :t").param("t", taskId)
                .query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
                        select :v, design_item_id, design_item_version_id, relation
                          from task_plan_design_ref where plan_revision_id = :r
                        """).param("v", validationId).param("r", revisionId).update();
        return validationId;
    }

    private String stateJson(String event, long revisionId, long lock, Long validationId, String extra) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event\":\"").append(event).append("\",");
        sb.append("\"plan_revision_id\":").append(revisionId).append(",");
        sb.append("\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":").append(validationId);
        }
        if (extra != null) {
            sb.append(",").append(extra);
        }
        sb.append("}");
        return sb.toString();
    }

    private HttpResponse<String> report(long taskId, String json, Long account) throws Exception {
        return api.post(tasksPath() + "/" + taskId + "/state-events", json, account);
    }

    private String changesRef(String key) {
        return """
                [{"design_item_id": %d, "design_item_version_id": %d, "relation": "CHANGES",
                  "proposed_content": {"method":"GET","path":"/x","response":{"memberId":"long"}}}]
                """.formatted(api.designItemId(key), api.currentVersionId(key));
    }

    private String status(long taskId) {
        return api.jdbc.sql("select status from task where id = :t").param("t", taskId).query(String.class).single();
    }

    // ------------------------------------------------------------------
    // START 게이트
    // ------------------------------------------------------------------

    @Test
    void start_withPass_becomesInProgress_andRepeatIsIdempotent() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);

        HttpResponse<String> first = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(first.body(), "status")).isEqualTo("IN_PROGRESS");
        assertThat(TaskApiSupport.longField(first.body(), "started_validation_id")).isEqualTo(v);

        // 같은 요청 재전송 (lock_version 이 이미 바뀐 뒤라도) → 200, 상태 그대로
        HttpResponse<String> again = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(again.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void start_withoutPass_is409ValidationNotPassed() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        // NEEDS_INPUT 검증만 있음
        Long baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        values (:t, :r, :b, 'NEEDS_INPUT', 'AGENT', 's', 'r1')
                        """).param("t", t[0]).param("r", t[1]).param("b", baselineId).update();
        Long v = api.jdbc.sql("select max(id) from validation").query(Long.class).single();

        HttpResponse<String> res = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"validation_not_passed\"");
        assertThat(status(t[0])).isEqualTo("READY");
    }

    @Test
    void start_withoutValidationId_is400() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        HttpResponse<String> res = report(t[0], stateJson("START", t[1], t[2], null, null), member);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("validation_id");
    }

    @Test
    void start_afterDesignChanged_is409VersionConflict_butUnrelatedTaskStarts() throws Exception {
        long[] a = createTask("API-01 읽음", api.readsRef("API-01"), "[]", member);
        long[] b = createTask("API-02 읽음", api.readsRef("API-02"), "[]", member);
        long va = passValidation(a[0], a[1]);
        long vb = passValidation(b[0], b[1]);

        // API-01 v2 발행: 새 버전 + baseline v2 (API-01→v2, API-02→v1) + current 교체
        api.jdbc.sql("""
                        insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                        select id, 2, 'APPROVED', 'GET /users v2', 1, '{"method":"GET","path":"/users","v":2}', 'h-api01-v2', 'HUMAN', now()
                          from design_item where key = 'API-01'
                        """).update();
        api.jdbc.sql("""
                        insert into baseline (project_id, version_no, published_by)
                        select :p, 2, id from account where email = 'leader@test'
                        """).param("p", projectId).update();
        api.jdbc.sql("""
                        insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id)
                        select b.id, b.project_id, di.id,
                               (select id from design_item_version v where v.design_item_id = di.id order by version_no desc limit 1)
                          from baseline b join design_item di on di.project_id = b.project_id
                         where b.version_no = 2
                        """).update();
        api.jdbc.sql("update project set current_baseline_id = (select id from baseline where version_no = 2) where id = :p")
                .param("p", projectId).update();

        HttpResponse<String> resA = report(a[0], stateJson("START", a[1], a[2], va, null), member);
        assertThat(resA.statusCode()).isEqualTo(409);
        assertThat(resA.body()).contains("\"code\":\"version_conflict\"");

        // 전역 baseline 번호가 바뀌었어도 내가 참조한 API-02 는 그대로 → 시작 가능 (D4)
        HttpResponse<String> resB = report(b[0], stateJson("START", b[1], b[2], vb, null), member);
        assertThat(resB.statusCode()).as(resB.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(resB.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void start_withIncompleteBlockingDependency_is409() throws Exception {
        long[] dep = createTask("선행", api.readsRef("API-01"), "[]", member);
        long[] t = createTask("후행", api.readsRef("API-02"), "[" + dep[0] + "]", member);
        long v = passValidation(t[0], t[1]);

        HttpResponse<String> res = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"dependency_not_completed\"").contains("T-1");
    }

    @Test
    void concurrentStart_onSameChangesItem_onlyOneWins() throws Exception {
        long[] t1 = createTask("T1 API-01 변경", changesRef("API-01"), "[]", member);
        long[] t2 = createTask("T2 API-01 변경", changesRef("API-01"), "[]", leader);
        long v1 = passValidation(t1[0], t1[1]);
        long v2 = passValidation(t2[0], t2[1]);

        // 두 스레드가 같은 순간 START. project 행 잠금으로 직렬화되어 하나만 통과해야 한다.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Callable<HttpResponse<String>>> calls = new ArrayList<>();
        calls.add(() -> {
            go.await();
            return report(t1[0], stateJson("START", t1[1], t1[2], v1, null), member);
        });
        calls.add(() -> {
            go.await();
            return report(t2[0], stateJson("START", t2[1], t2[2], v2, null), leader);
        });
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (Callable<HttpResponse<String>> c : calls) {
            futures.add(pool.submit(c));
        }
        go.countDown();
        int ok = 0;
        int conflict = 0;
        for (Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get();
            if (r.statusCode() == 200) {
                ok++;
            } else if (r.statusCode() == 409 && r.body().contains("\"code\":\"task_conflict\"")) {
                conflict++;
            } else {
                throw new AssertionError("예상 밖 응답: " + r.statusCode() + " " + r.body());
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
        assertThat(conflict).isEqualTo(1);
        Integer inProgress = api.jdbc.sql("select count(*) from task where status = 'IN_PROGRESS'").query(Integer.class).single();
        assertThat(inProgress).isEqualTo(1);
    }

    @Test
    void readsOnSameItem_doNotConflict() throws Exception {
        long[] t1 = createTask("T1 읽기", api.readsRef("API-01"), "[]", member);
        long[] t2 = createTask("T2 읽기", api.readsRef("API-01"), "[]", leader);
        long v1 = passValidation(t1[0], t1[1]);
        long v2 = passValidation(t2[0], t2[1]);

        assertThat(report(t1[0], stateJson("START", t1[1], t1[2], v1, null), member).statusCode()).isEqualTo(200);
        assertThat(report(t2[0], stateJson("START", t2[1], t2[2], v2, null), leader).statusCode()).isEqualTo(200);
    }

    // ------------------------------------------------------------------
    // WAIT / RESUME / COMPLETED / CANCEL
    // ------------------------------------------------------------------

    @Test
    void wait_onlyFromInProgress_andRevalidationIsRejected() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);

        // READY 에서 WAIT → 400
        HttpResponse<String> early = report(t[0], stateJson("WAIT", t[1], t[2], null, "\"waiting_reason\":\"INPUT\""), member);
        assertThat(early.statusCode()).isEqualTo(400);
        assertThat(early.body()).contains("\"code\":\"invalid_transition\"");

        report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = api.jdbc.sql("select lock_version from task where id = :t").param("t", t[0]).query(Long.class).single();

        // REVALIDATION 은 시스템 전용 → 400
        HttpResponse<String> sys = report(t[0], stateJson("WAIT", t[1], lock, null, "\"waiting_reason\":\"REVALIDATION\""), member);
        assertThat(sys.statusCode()).isEqualTo(400);

        HttpResponse<String> ok = report(t[0], stateJson("WAIT", t[1], lock, null, "\"waiting_reason\":\"APPROVAL\""), member);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(ok.body(), "status")).isEqualTo("WAITING");
        assertThat(TaskApiSupport.stringField(ok.body(), "waiting_reason")).isEqualTo("APPROVAL");

        // RESUME 는 게이트를 다시 지난다 — 같은 PASS 로 통과
        long lock2 = TaskApiSupport.longField(ok.body(), "lock_version");
        HttpResponse<String> resumed = report(t[0], stateJson("RESUME", t[1], lock2, v, null), member);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(resumed.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void completed_withoutDelta_isCompleted_andReportSaved() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = TaskApiSupport.longField(started.body(), "lock_version");

        String extra = "\"summary\":\"끝\",\"delta\":[],\"completion_checks\":[{\"criterion\":\"테스트 통과\",\"met\":true}]";
        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], lock, null, extra), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(res.body(), "status")).isEqualTo("COMPLETED");

        Integer reportsCount = api.jdbc.sql("select count(*) from task_completion_report where task_id = :t and has_delta = false")
                .param("t", t[0]).query(Integer.class).single();
        assertThat(reportsCount).isEqualTo(1);

        // 완료 보고 재전송 → 멱등 200
        HttpResponse<String> again = report(t[0], stateJson("COMPLETED", t[1], lock, null, extra), member);
        assertThat(again.statusCode()).isEqualTo(200);
    }

    @Test
    void completed_withDelta_holdsForRevalidation() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = TaskApiSupport.longField(started.body(), "lock_version");

        String extra = "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"응답에 nickname 추가\"}]";
        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], lock, null, extra), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(res.body(), "status")).isEqualTo("WAITING");
        assertThat(TaskApiSupport.stringField(res.body(), "waiting_reason")).isEqualTo("REVALIDATION");
        Boolean hasDelta = api.jdbc.sql("select has_delta from task_completion_report where task_id = :t").param("t", t[0])
                .query(Boolean.class).single();
        assertThat(hasDelta).isTrue();
    }

    @Test
    void cancel_byOtherMember_is403_butHumanLeaderCan_andAgentOfLeaderCannot() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = api.jdbc.sql("select lock_version from task where id = :t").param("t", t[0]).query(Long.class).single();

        // 팀장의 에이전트 토큰(여기선 member 토큰을 leader 계정에 붙여 흉내) → 사람이 아니므로 403
        HttpResponse<String> byAgent = api.postAsAgent(tasksPath() + "/" + t[0] + "/state-events",
                stateJson("CANCEL", t[1], lock, null, null), leader, api.agentTokenId());
        assertThat(byAgent.statusCode()).isEqualTo(403);

        HttpResponse<String> byLeader = report(t[0], stateJson("CANCEL", t[1], lock, null, null), leader);
        assertThat(byLeader.statusCode()).as(byLeader.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(byLeader.body(), "status")).isEqualTo("CANCELLED");

        // 점유 해제·늦은 결과 차단: PASS 였던 검증이 STALE
        String vs = api.jdbc.sql("select status from validation where id = :v").param("v", v).query(String.class).single();
        assertThat(vs).isEqualTo("STALE");

        // 취소 뒤 같은 (task, validation) 으로 START 재전송 → "기존 결과 반환" (멱등). 상태는 그대로 CANCELLED
        HttpResponse<String> restart = report(t[0], stateJson("START", t[1], lock + 1, v, null), member);
        assertThat(restart.statusCode()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(restart.body(), "status")).isEqualTo("CANCELLED");
        // 다른 검증으로 새로 시작하려는 시도는 금지 전이 → 400
        long v2 = passValidation(t[0], t[1]);
        HttpResponse<String> fresh = report(t[0], stateJson("START", t[1], lock + 1, v2, null), member);
        assertThat(fresh.statusCode()).isEqualTo(400);
        assertThat(fresh.body()).contains("\"code\":\"invalid_transition\"");
    }

    @Test
    void staleLockVersion_is409() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> res = report(t[0], stateJson("START", t[1], t[2] + 5, v, null), member);
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"revision_conflict\"");
    }
}
