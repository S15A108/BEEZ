package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.dto.TaskStateInput;
import com.beez.backend.task.service.TaskStateService;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * S15P31A108-158 상태 전이·시작 게이트. 지난주 DB 에서 SQL 로 검증한 시나리오(동시 START, 멱등, 무효화)를 HTTP 로 옮겼다.
 * 검증 실행 기능(-59)은 아직 없어 PASS 검증 행은 SQL 로 만든다.
 * 에이전트 호출은 MCP 전용이라 REST 로 보낼 수 없어(401), MCP 어댑터(-176)처럼 서비스를 직접 부른다.
 */
class TaskStateApiIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskStateService stateService;

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
        sb.append("\"plan_revision_id\":\"").append(revisionId).append("\",");
        sb.append("\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":\"").append(validationId).append("\"");
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
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "CHANGES",
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
    void start_withOtherProjectsValidation_looksSameAsMissing() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        api.runSql("fixtures/other-project-validation.sql");
        Long otherValidation = api.jdbc.sql("""
                        select v.id from validation v join task t on t.id = v.task_id join project p on p.id = t.project_id
                         where p.name = 'Q'
                        """).query(Long.class).single();

        HttpResponse<String> foreign = report(t[0], stateJson("START", t[1], t[2], otherValidation, null), member);
        HttpResponse<String> missing = report(t[0], stateJson("START", t[1], t[2], 999999L, null), member);

        assertThat(foreign.statusCode()).isEqualTo(409);
        assertThat(foreign.body()).contains("\"code\":\"validation_not_passed\"");
        // 남의 검증 번호와 없는 번호의 응답이 글자까지 같다 = Q 검증의 내용(설계 항목·최신성)이 하나도 안 섞였다
        assertThat(foreign.body()).isEqualTo(missing.body());
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
        long[] t = createTask("후행", api.readsRef("API-02"), "[\"" + dep[0] + "\"]", member);
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
    void completed_withMalformedCompletionCheck_is400() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = TaskApiSupport.longField(started.body(), "lock_version");
        // met 누락 (명세 CompletionCheck: criterion·met 필수)
        String extra = "\"completion_checks\":[{\"criterion\":\"테스트 통과\"}]";
        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], lock, null, extra), member);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"validation_failed\"");
    }

    @Test
    void completed_withDelta_holdsForRevalidation() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = TaskApiSupport.longField(started.body(), "lock_version");

        String extra = "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"응답에 nickname 추가\"}]";
        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], lock, null, extra), member);
        // 명세 TSK-A07: delta 가 있어 검증을 접수한 경우 202
        assertThat(res.statusCode()).as(res.body()).isEqualTo(202);
        assertThat(TaskApiSupport.stringField(res.body(), "status")).isEqualTo("WAITING");
        assertThat(TaskApiSupport.stringField(res.body(), "waiting_reason")).isEqualTo("REVALIDATION");
        Boolean hasDelta = api.jdbc.sql("select has_delta from task_completion_report where task_id = :t").param("t", t[0])
                .query(Boolean.class).single();
        assertThat(hasDelta).isTrue();

        // 응답을 잃어버려 같은 완료 보고를 다시 보냄 → 같은 결과(202), 완료 보고는 1건 그대로
        HttpResponse<String> again = report(t[0], stateJson("COMPLETED", t[1], lock, null, extra), member);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(202);
        Integer reportCount = api.jdbc.sql("select count(*) from task_completion_report where task_id = :t").param("t", t[0])
                .query(Integer.class).single();
        assertThat(reportCount).isEqualTo(1);
    }

    /**
     * 깨지면: 팀장 위임(MEM-A08)이 커밋되는 동안 프로젝트 잠금을 기다린 팀장 CANCEL 이 이미 팀장이 아닌 사람으로 처리된다
     * (-212 리뷰 "팀장 전용 요청은 잠금 뒤 팀장 권한 재확인"). 취소는 되돌릴 수 없어 더 중요하다.
     * 프로젝트 행을 쥔 별도 트랜잭션에서 위임을 흉내 내 커밋해, 겹치는 순서를 매번 똑같이 만든다 (ProjectMemberApiIntegrationTests 와 같은 방식).
     */
    @Test
    void cancel_byLeaderWaitingOnLock_isRejectedAfterTransfer() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        report(t[0], stateJson("START", t[1], t[2], v, null), member);
        api.jdbc.sql("update task set updated_at = now() - interval '1 hour' where id = :t").param("t", t[0]).update();
        long lock = api.jdbc.sql("select lock_version from task where id = :t").param("t", t[0]).query(Long.class).single();
        long leaderMemberId = api.memberId("leader@test");
        long memberMemberId = api.memberId("member@test");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        HttpResponse<String> res;
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (PreparedStatement ps = held.prepareStatement("select id from project where id = ? for update")) {
                ps.setLong(1, projectId);
                ps.executeQuery();
            }
            // 팀장이 보낸 CANCEL 은 프로젝트 잠금에서 기다린다
            Future<HttpResponse<String>> pending = pool.submit(() -> report(t[0], stateJson("CANCEL", t[1], lock, null, null), leader));
            awaitBlockedOnLock();
            // 그 사이 위임: 활성 팀장은 하나(ux_member_one_leader)라 내리는 쪽이 먼저
            try (PreparedStatement ps = held.prepareStatement("update project_member set role = ? where id = ?")) {
                ps.setString(1, "MEMBER");
                ps.setLong(2, leaderMemberId);
                ps.executeUpdate();
                ps.setString(1, "LEADER");
                ps.setLong(2, memberMemberId);
                ps.executeUpdate();
            }
            held.commit();
            res = pending.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 역할 원복 (다른 시험에 영향 없게)
        api.jdbc.sql("update project_member set role = 'MEMBER' where id = :m").param("m", memberMemberId).update();
        api.jdbc.sql("update project_member set role = 'LEADER' where id = :m").param("m", leaderMemberId).update();

        assertThat(res.statusCode()).as(res.body()).isEqualTo(403);
        assertThat(res.body()).contains("\"code\":\"forbidden\"");
        assertThat(status(t[0])).isEqualTo("IN_PROGRESS");   // 취소되지 않았다
    }

    /** 다른 세션이 행 잠금을 기다릴 때까지 (최대 10초). 기다리지 않으면 그 요청이 그 행을 잠그지 않는다는 뜻이다 */
    private void awaitBlockedOnLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (api.jdbc.sql("""
                        select count(*) from pg_stat_activity
                         where wait_event_type = 'Lock' and datname = current_database()
                        """).query(Long.class).single() == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("요청이 행 잠금에서 기다리지 않았다");
            }
            Thread.sleep(20);
        }
    }

    @Test
    void cancel_leaderOnlyWhenStalled_agentOfLeaderNever() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = api.jdbc.sql("select lock_version from task where id = :t").param("t", t[0]).query(Long.class).single();

        // 방금 보고가 있었던 작업 → 팀장(사람)도 취소 불가 (AC-14 는 "보고가 끊긴 작업"만)
        HttpResponse<String> fresh = report(t[0], stateJson("CANCEL", t[1], lock, null, null), leader);
        assertThat(fresh.statusCode()).isEqualTo(403);
        assertThat(fresh.body()).contains("보고가 끊긴");

        // 마지막 보고를 1시간 전으로 돌림 (임계값 기본 30분)
        api.jdbc.sql("update task set updated_at = now() - interval '1 hour' where id = :t").param("t", t[0]).update();

        // 팀장 본인의 에이전트 토큰 → 사람이 아니므로 끊긴 작업이라도 403
        TaskStateInput cancel = json.readValue(stateJson("CANCEL", t[1], lock, null, null), TaskStateInput.class);
        assertThatThrownBy(() -> stateService.report(CallerContext.agent(leader, api.leaderAgentTokenId()),
                projectId, t[0], cancel))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("forbidden");
                });

        // 사람 팀장 → 취소됨
        HttpResponse<String> byLeader = report(t[0], stateJson("CANCEL", t[1], lock, null, null), leader);
        assertThat(byLeader.statusCode()).as(byLeader.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(byLeader.body(), "status")).isEqualTo("CANCELLED");

        // 점유 해제·늦은 결과 차단: PASS 였던 검증이 STALE
        String vs = api.jdbc.sql("select status from validation where id = :v").param("v", v).query(String.class).single();
        assertThat(vs).isEqualTo("STALE");

        // 팀장이 같은 취소를 다시 보냄 → 멱등 200
        HttpResponse<String> cancelAgain = report(t[0], stateJson("CANCEL", t[1], lock, null, null), leader);
        assertThat(cancelAgain.statusCode()).isEqualTo(200);

        // 취소된 작업에 START → START 멱등은 IN_PROGRESS 일 때만이라 금지 전이 400 (같은 검증이든 새 검증이든)
        HttpResponse<String> restart = report(t[0], stateJson("START", t[1], lock + 1, v, null), member);
        assertThat(restart.statusCode()).isEqualTo(400);
        assertThat(restart.body()).contains("\"code\":\"invalid_transition\"");
    }

    @Test
    void cancel_waitingTaskIsNotStalled_evenAfterThreshold() throws Exception {
        long[] t = createTask("승인 대기", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = TaskApiSupport.longField(started.body(), "lock_version");
        HttpResponse<String> waiting = report(t[0], stateJson("WAIT", t[1], lock, null, "\"waiting_reason\":\"APPROVAL\""), member);
        long lock2 = TaskApiSupport.longField(waiting.body(), "lock_version");
        api.jdbc.sql("update task set updated_at = now() - interval '3 hour' where id = :t").param("t", t[0]).update();

        // 대기 중에는 보고가 멈추는 게 정상 → 팀장도 취소 불가 (§4.1)
        HttpResponse<String> res = report(t[0], stateJson("CANCEL", t[1], lock2, null, null), leader);
        assertThat(res.statusCode()).isEqualTo(403);
    }

    @Test
    void planChange_whileWaitingForDependency_keepsReason_andResumeNeedsNewPass() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", member);
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = report(t[0], stateJson("START", t[1], t[2], v, null), member);
        long lock = TaskApiSupport.longField(started.body(), "lock_version");
        HttpResponse<String> waiting = report(t[0], stateJson("WAIT", t[1], lock, null, "\"waiting_reason\":\"DEPENDENCY\""), member);
        long lock2 = TaskApiSupport.longField(waiting.body(), "lock_version");

        // 대기 중에 계획 변경 (S2 는 IN_PROGRESS 만 상태를 바꾼다)
        String plan = api.planJson("A v2", api.readsRef("API-02"), "[]").trim();
        String body = plan.substring(0, plan.lastIndexOf('}'))
                + ", \"expected_revision_no\": 1, \"expected_lock_version\": " + lock2 + "}";
        HttpResponse<String> changed = api.post(tasksPath() + "/" + t[0] + "/plan-revisions", body, member);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(changed.body(), "status")).isEqualTo("WAITING");
        assertThat(TaskApiSupport.stringField(changed.body(), "waiting_reason")).isEqualTo("DEPENDENCY");
        String vs = api.jdbc.sql("select status from validation where id = :v").param("v", v).query(String.class).single();
        assertThat(vs).isEqualTo("STALE");

        // 옛 PASS 로 RESUME → 새 리비전엔 PASS 가 없으므로 409
        long newRevision = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", t[0])
                .query(Long.class).single();
        long lock3 = TaskApiSupport.longField(changed.body(), "lock_version");
        HttpResponse<String> resume = report(t[0], stateJson("RESUME", newRevision, lock3, v, null), member);
        assertThat(resume.statusCode()).isEqualTo(409);
        assertThat(resume.body()).contains("\"code\":\"validation_not_passed\"");

        // 새 리비전 PASS 로 RESUME → 진행
        long v2 = passValidation(t[0], newRevision);
        HttpResponse<String> ok = report(t[0], stateJson("RESUME", newRevision, lock3, v2, null), member);
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(ok.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void cancel_byOtherMember_is403() throws Exception {
        long[] t = createTask("A", api.readsRef("API-01"), "[]", leader);
        // 담당자가 아닌 일반 멤버 → 끊긴 작업이어도 팀장이 아니므로 403
        HttpResponse<String> res = report(t[0], stateJson("CANCEL", t[1], t[2], null, null), member);
        assertThat(res.statusCode()).isEqualTo(403);
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
