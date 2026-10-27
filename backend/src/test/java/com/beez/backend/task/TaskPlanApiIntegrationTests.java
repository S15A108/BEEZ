package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.service.TaskAssigneeService;
import com.beez.backend.task.service.TaskPlanService;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * S15P31A108-156 작업 계획 등록·수정·조회. 실제 Postgres(pgvector pg17) + Flyway 위에서 HTTP 로 검증한다.
 * 규칙 출처: docs/design/04-task-lifecycle.md §4, docs/api/01-backend-rest.md TSK-A01~A06.
 * 에이전트 호출은 MCP 전용이라 REST 로 보낼 수 없어(401), MCP 어댑터(-176)처럼 서비스를 직접 부른다.
 */
class TaskPlanApiIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    TaskPlanService planService;

    @Autowired
    TaskAssigneeService assigneeService;

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

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    // ------------------------------------------------------------------
    // TSK-A01 생성
    // ------------------------------------------------------------------

    @Test
    void create_savesFirstRevision_andBecomesReady() throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("로그인 API", api.readsRef("API-01"), "[]"), member);

        assertThat(res.statusCode()).isEqualTo(201);
        String body = res.body();
        assertThat(TaskApiSupport.stringField(body, "key")).isEqualTo("T-1");
        assertThat(TaskApiSupport.stringField(body, "status")).isEqualTo("READY");
        assertThat(TaskApiSupport.stringField(body, "validation_status")).isEqualTo("NOT_RUN");
        assertThat(TaskApiSupport.longField(body, "assignee_member_id")).isEqualTo(api.memberId("member@test"));
        assertThat(TaskApiSupport.longField(body, "revision_no")).isEqualTo(1);
        assertThat(body).contains("\"relation\":\"READS\"");
        // snake_case 로 나가는지
        assertThat(body).contains("\"current_plan_revision\"").doesNotContain("currentPlanRevision");
        // ID 는 십진 문자열, 번호는 정수 (명세 00-common 3절)
        assertThat(body).contains("\"id\":\"").contains("\"project_id\":\"").contains("\"assignee_member_id\":\"")
                .contains("\"lock_version\":").doesNotContain("\"lock_version\":\"")
                .contains("\"revision_no\":1");
    }

    @Test
    void create_secondTask_getsNextKey() throws Exception {
        api.post(tasksPath(), api.planJson("첫째", api.readsRef("API-01"), "[]"), member);
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("둘째", api.readsRef("API-02"), "[]"), leader);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(res.body(), "key")).isEqualTo("T-2");
    }

    @Test
    void create_byNonMember_isHiddenAs404() throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("몰래", api.readsRef("API-01"), "[]"), outsider);

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("\"code\":\"not_found\"");
    }

    @Test
    void create_withoutLogin_is401() throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("익명", api.readsRef("API-01"), "[]"), null);

        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"unauthenticated\"");
    }

    @Test
    void create_changesWithoutProposedContent_is400() throws Exception {
        String refs = """
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "CHANGES"}]
                """.formatted(api.designItemId("API-01"), api.currentVersionId("API-01"));
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("변경", refs, "[]"), member);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"validation_failed\"").contains("proposed_content");
    }

    @Test
    void create_withStaleVersion_is409VersionConflict() throws Exception {
        long wrongVersion = api.currentVersionId("API-01") + 999;
        String refs = """
                [{"design_item_id": "%d", "design_item_version_id": "%d", "relation": "READS"}]
                """.formatted(api.designItemId("API-01"), wrongVersion);
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("낡은 참조", refs, "[]"), member);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"version_conflict\"");
    }

    @Test
    void create_duplicateItem_keepsStrongestRelationOnly() throws Exception {
        long item = api.designItemId("API-01");
        long version = api.currentVersionId("API-01");
        String refs = """
                [
                  {"design_item_id": "%d", "design_item_version_id": "%d", "relation": "READS"},
                  {"design_item_id": "%d", "design_item_version_id": "%d", "relation": "CHANGES",
                   "proposed_content": {"method": "GET", "path": "/users", "response": {"memberId": "long"}}}
                ]
                """.formatted(item, version, item, version);
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("겹침", refs, "[]"), member);

        assertThat(res.statusCode()).isEqualTo(201);
        Integer rows = api.jdbc.sql("select count(*) from task_plan_design_ref").query(Integer.class).single();
        assertThat(rows).isEqualTo(1);
        String relation = api.jdbc.sql("select relation from task_plan_design_ref").query(String.class).single();
        assertThat(relation).isEqualTo("CHANGES");
    }

    @Test
    void create_withSelfOrForeignDependency_is400() throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("없는 선행", api.readsRef("API-01"), "[\"99999\"]"), member);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"validation_failed\"");
    }

    @Test
    void create_asAgent_recordsTokenOnRevision() throws Exception {
        TaskPlanInput input = json.readValue(api.planJson("에이전트 작성", api.readsRef("API-01"), "[]"), TaskPlanInput.class);

        String body = json.writeValueAsString(
                planService.createTask(CallerContext.agent(member, api.agentTokenId()), projectId, input));

        assertThat(TaskApiSupport.longField(body, "agent_token_id")).isEqualTo(api.agentTokenId());
        assertThat(TaskApiSupport.longField(body, "created_by")).isEqualTo(member);
    }

    // ------------------------------------------------------------------
    // TSK-A04 리비전 추가
    // ------------------------------------------------------------------

    @Test
    void addRevision_appendsRevision2_andDoesNotTouchRevision1() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        long lockVersion = TaskApiSupport.longField(created.body(), "lock_version");

        String body = revisionJson("v2", api.readsRef("API-02"), 1, (int) lockVersion);
        HttpResponse<String> res = api.post(tasksPath() + "/" + taskId + "/plan-revisions", body, member);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(TaskApiSupport.longField(res.body(), "revision_no")).isEqualTo(2);
        assertThat(TaskApiSupport.stringField(res.body(), "title")).isEqualTo("v2");

        Integer revisionCount = api.jdbc.sql("select count(*) from task_plan_revision where task_id = :t")
                .param("t", taskId).query(Integer.class).single();
        assertThat(revisionCount).isEqualTo(2);
        String firstTitle = api.jdbc.sql("select title from task_plan_revision where task_id = :t and revision_no = 1")
                .param("t", taskId).query(String.class).single();
        assertThat(firstTitle).isEqualTo("v1");
    }

    @Test
    void addRevision_withWrongExpectedRevisionNo_is409() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        long lockVersion = TaskApiSupport.longField(created.body(), "lock_version");

        HttpResponse<String> res = api.post(tasksPath() + "/" + taskId + "/plan-revisions",
                revisionJson("v2", api.readsRef("API-02"), 7, (int) lockVersion), member);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"revision_conflict\"");
    }

    @Test
    void addRevision_byOtherMember_is403() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        long lockVersion = TaskApiSupport.longField(created.body(), "lock_version");

        HttpResponse<String> res = api.post(tasksPath() + "/" + taskId + "/plan-revisions",
                revisionJson("v2", api.readsRef("API-02"), 1, (int) lockVersion), leader);

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("\"code\":\"forbidden\"");
    }

    @Test
    void addRevision_whileInProgress_marksValidationStale_andHoldsForRevalidation() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        // 리비전 id 는 DB 에서 직접 가져온다 (응답 JSON 에는 작업 id 와 리비전 id 가 둘 다 "id" 라 문자열로 고르기 어렵다)
        Long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();

        // 검증 실행 기능(-59)은 아직 없으므로 PASS 행과 진행 중 상태를 SQL 로 만든다.
        Long baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        values (:t, :r, :b, 'PASS', 'AGENT', 'snap', 'r1')
                        """).param("t", taskId).param("r", revisionId).param("b", baselineId).update();
        Long validationId = api.jdbc.sql("select max(id) from validation").query(Long.class).single();
        api.jdbc.sql("""
                        update task set status = 'IN_PROGRESS', started_validation_id = :v, started_at = now(), lock_version = lock_version + 1
                         where id = :t
                        """).param("v", validationId).param("t", taskId).update();
        Integer lockVersion = api.jdbc.sql("select lock_version from task where id = :t").param("t", taskId)
                .query(Integer.class).single();

        HttpResponse<String> res = api.post(tasksPath() + "/" + taskId + "/plan-revisions",
                revisionJson("v2 진행 중 변경", api.readsRef("API-02"), 1, lockVersion), member);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(res.body(), "status")).isEqualTo("WAITING");
        assertThat(TaskApiSupport.stringField(res.body(), "waiting_reason")).isEqualTo("REVALIDATION");
        String validationStatus = api.jdbc.sql("select status from validation where id = :v").param("v", validationId)
                .query(String.class).single();
        assertThat(validationStatus).isEqualTo("STALE");
        // 점유는 유지: started_validation_id 그대로
        Long stillStarted = api.jdbc.sql("select started_validation_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        assertThat(stillStarted).isEqualTo(validationId);
    }

    @Test
    void addRevision_concurrentWithSameExpectedNo_oneWins_otherIs409() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        int lockVersion = (int) TaskApiSupport.longField(created.body(), "lock_version");
        String path = tasksPath() + "/" + taskId + "/plan-revisions";

        // 같은 expected_revision_no=1 로 두 요청을 동시에 보낸다
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<HttpResponse<String>>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String body = revisionJson("동시 " + i, api.readsRef("API-02"), 1, lockVersion);
            futures.add(pool.submit(() -> {
                go.await();
                return api.post(path, body, member);
            }));
        }
        go.countDown();
        int created201 = 0;
        int conflict409 = 0;
        for (java.util.concurrent.Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get();
            if (r.statusCode() == 201) {
                created201++;
            } else if (r.statusCode() == 409 && r.body().contains("\"code\":\"revision_conflict\"")) {
                conflict409++;
            } else {
                throw new AssertionError("예상 밖 응답: " + r.statusCode() + " " + r.body());
            }
        }
        pool.shutdown();

        assertThat(created201).isEqualTo(1);
        assertThat(conflict409).isEqualTo(1);
        Integer revisions = api.jdbc.sql("select count(*) from task_plan_revision where task_id = :t").param("t", taskId)
                .query(Integer.class).single();
        assertThat(revisions).isEqualTo(2);
    }

    @Test
    void addRevision_dependencyCycle_is409DependencyCycle() throws Exception {
        HttpResponse<String> first = api.post(tasksPath(), api.planJson("T1", api.readsRef("API-01"), "[]"), member);
        long t1 = TaskApiSupport.longField(first.body(), "id");
        int t1Lock = (int) TaskApiSupport.longField(first.body(), "lock_version");
        // T2 는 T1 을 선행으로 둔다
        HttpResponse<String> second = api.post(tasksPath(), api.planJson("T2", api.readsRef("API-02"), "[\"" + t1 + "\"]"), member);
        assertThat(second.statusCode()).isEqualTo(201);
        long t2 = TaskApiSupport.longField(second.body(), "id");

        // T1 이 T2 를 선행으로 두려 하면 순환 → 409 dependency_cycle (명세 TSK-A04)
        HttpResponse<String> res = api.post(tasksPath() + "/" + t1 + "/plan-revisions",
                revisionJsonWithDeps("T1 v2", api.readsRef("API-01"), "[\"" + t2 + "\"]", 1, t1Lock), member);
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"dependency_cycle\"");
    }

    @Test
    void designRef_whenNoBaselinePublished_is400() throws Exception {
        String refs = api.readsRef("API-01"); // 기준을 지우기 전에 참조 JSON 을 만든다
        api.jdbc.sql("update project set current_baseline_id = null where id = :p").param("p", projectId).update();
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("기준 없음", refs, "[]"), member);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"validation_failed\"");
    }

    @Test
    void create_withNonNumericId_is400() throws Exception {
        String refs = """
                [{"design_item_id": "abc", "design_item_version_id": "1", "relation": "READS"}]
                """;
        HttpResponse<String> res = api.post(tasksPath(), api.planJson("형식", refs, "[]"), member);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("design_item_id");
    }

    @Test
    void list_validationStatusFilter_isAppliedBeforePaging() throws Exception {
        // T-1 은 PASS, T-2·T-3 은 검증 없음(NOT_RUN). 최신순 limit=1 이면 PASS 는 첫 페이지에 와야 한다
        HttpResponse<String> c1 = api.post(tasksPath(), api.planJson("1", api.readsRef("API-01"), "[]"), member);
        api.post(tasksPath(), api.planJson("2", api.readsRef("API-01"), "[]"), member);
        api.post(tasksPath(), api.planJson("3", api.readsRef("API-01"), "[]"), member);
        long t1 = TaskApiSupport.longField(c1.body(), "id");
        Long rev = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", t1).query(Long.class).single();
        Long baseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        values (:t, :r, :b, 'PASS', 'AGENT', 's', 'r1')
                        """).param("t", t1).param("r", rev).param("b", baseline).update();

        HttpResponse<String> pass = api.get(tasksPath() + "?validation_status=PASS&limit=1", leader);
        assertThat(pass.body()).contains("\"key\":\"T-1\"").contains("\"has_more\":false");
        HttpResponse<String> notRun = api.get(tasksPath() + "?validation_status=NOT_RUN&limit=1", leader);
        assertThat(notRun.body()).contains("\"key\":\"T-3\"").contains("\"has_more\":true");
        HttpResponse<String> bad = api.get(tasksPath() + "?validation_status=FOO", leader);
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // 조회
    // ------------------------------------------------------------------

    @Test
    void list_pagesByCursor_newestFirst() throws Exception {
        api.post(tasksPath(), api.planJson("1", api.readsRef("API-01"), "[]"), member);
        api.post(tasksPath(), api.planJson("2", api.readsRef("API-01"), "[]"), member);
        api.post(tasksPath(), api.planJson("3", api.readsRef("API-01"), "[]"), member);

        HttpResponse<String> first = api.get(tasksPath() + "?limit=2", leader);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"has_more\":true").contains("\"key\":\"T-3\"").contains("\"key\":\"T-2\"")
                .doesNotContain("\"key\":\"T-1\"");
        String cursor = TaskApiSupport.stringField(first.body(), "next_cursor");

        HttpResponse<String> second = api.get(tasksPath() + "?limit=2&cursor=" + cursor, leader);
        assertThat(second.body()).contains("\"has_more\":false").contains("\"key\":\"T-1\"").doesNotContain("\"key\":\"T-2\"");
    }

    @Test
    void list_filtersByStatus_andRejectsUnknownStatus() throws Exception {
        api.post(tasksPath(), api.planJson("1", api.readsRef("API-01"), "[]"), member);

        HttpResponse<String> ready = api.get(tasksPath() + "?status=READY", leader);
        assertThat(ready.body()).contains("\"key\":\"T-1\"");
        HttpResponse<String> done = api.get(tasksPath() + "?status=COMPLETED", leader);
        assertThat(done.body()).doesNotContain("\"key\":\"T-1\"");
        HttpResponse<String> bad = api.get(tasksPath() + "?status=FOO", leader);
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    @Test
    void revisions_returnHistoricalRefs_notLatest() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        int lockVersion = (int) TaskApiSupport.longField(created.body(), "lock_version");
        api.post(tasksPath() + "/" + taskId + "/plan-revisions", revisionJson("v2", api.readsRef("API-02"), 1, lockVersion), member);

        HttpResponse<String> res = api.get(tasksPath() + "/" + taskId + "/plan-revisions", leader);
        assertThat(res.statusCode()).isEqualTo(200);
        String body = res.body();
        int v2 = body.indexOf("\"revision_no\":2");
        int v1 = body.indexOf("\"revision_no\":1");
        assertThat(v2).isGreaterThanOrEqualTo(0);
        assertThat(v1).isGreaterThan(v2); // 최신 순
        assertThat(body).contains("\"design_item_id\":\"" + api.designItemId("API-01") + "\"");
        assertThat(body).contains("\"design_item_id\":\"" + api.designItemId("API-02") + "\"");
    }

    @Test
    void getTask_fromOtherProjectId_is404() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");

        HttpResponse<String> res = api.get("/api/projects/" + (projectId + 100) + "/tasks/" + taskId, member);
        assertThat(res.statusCode()).isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // TSK-A06 담당자 변경
    // ------------------------------------------------------------------

    @Test
    void changeAssignee_onlyHumanLeader() throws Exception {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("v1", api.readsRef("API-01"), "[]"), member);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        long lockVersion = TaskApiSupport.longField(created.body(), "lock_version");
        String body = """
                {"assignee_member_id": "%d", "expected_lock_version": %d}
                """.formatted(api.memberId("leader@test"), lockVersion);

        HttpResponse<String> byMember = api.patch(tasksPath() + "/" + taskId + "/assignee", body, member);
        assertThat(byMember.statusCode()).isEqualTo(403);

        // 팀장의 에이전트 토큰이라도 사람이 아니면 거절 (403 forbidden). 메시지는 AccessErrors 로 통일
        assertThatThrownBy(() -> assigneeService.changeAssignee(CallerContext.agent(leader, api.leaderAgentTokenId()),
                projectId, taskId, api.memberId("leader@test"), (int) lockVersion))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("forbidden");
                });

        HttpResponse<String> byLeader = api.patch(tasksPath() + "/" + taskId + "/assignee", body, leader);
        assertThat(byLeader.statusCode()).isEqualTo(200);
        assertThat(TaskApiSupport.longField(byLeader.body(), "assignee_member_id")).isEqualTo(api.memberId("leader@test"));
    }

    // ------------------------------------------------------------------

    private String revisionJson(String title, String refsJson, int expectedRevisionNo, int expectedLockVersion) {
        return revisionJsonWithDeps(title, refsJson, "[]", expectedRevisionNo, expectedLockVersion);
    }

    private String revisionJsonWithDeps(String title, String refsJson, String depsJson, int expectedRevisionNo,
                                        int expectedLockVersion) {
        String plan = api.planJson(title, refsJson, depsJson).trim();
        // 마지막 '}' 앞에 충돌 검사 필드 두 개를 끼워 넣는다
        String withoutClose = plan.substring(0, plan.lastIndexOf('}'));
        return withoutClose + ",\n  \"expected_revision_no\": " + expectedRevisionNo
                + ",\n  \"expected_lock_version\": " + expectedLockVersion + "\n}";
    }
}
