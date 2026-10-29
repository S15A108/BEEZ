package com.beez.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-A06 ~ A10 (S15P31A108-212). Jira 완료 조건 4개 — 팀장만 표시 수정·위임·제외, 위임 직후 이전 팀장 403,
 * 제외·나가기 다음 요청부터 사용자·토큰 차단, 열린 작업이 남으면 409·인계하면 성공 — 과 Q05(끝난 이유 기록),
 * 진행 중 작업 인계 결정(WAITING(REVALIDATION) 으로 내리고 이전 검증 STALE)을 실제 DB·필터 체인 위에서 확인한다.
 * 작업이 필요해 작업 테스트와 같은 픽스처(project-basic.sql: leader@test·member@test·outsider@test, 프로젝트 P)를 쓴다.
 */
class ProjectMemberApiIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    @Autowired
    ProjectAccessChecker access;

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

    /** 깨지면: 나간 사람이 팀원 목록에 남거나 비멤버가 팀 구성을 보거나, 에이전트 연결 표시가 틀린다 (MEM-A06). */
    @Test
    void list_showsActiveMembersOnly_withAgentConnection() {
        long gone = joinAsMember();
        fixtures().removeMember(memberIdOf(gone));
        // member 의 토큰에 세션이 있으면 연결로 본다 (-173 A06 합의)
        api.jdbc.sql("insert into agent_session (agent_token_id, session_key) values (:t, 's1')")
                .param("t", api.agentTokenId()).update();

        JsonNode page = read(api.get(membersPath(), member));
        assertThat(names(page)).containsExactlyInAnyOrder("팀장", "팀원");
        for (JsonNode m : page.get("items")) {
            assertThat(m.get("agent_connected").asBoolean()).isEqualTo(m.get("name").asString().equals("팀원"));
        }
        JsonNode first = read(api.get(membersPath() + "?limit=1", member));
        assertThat(first.get("has_more").asBoolean()).isTrue();
        JsonNode second = read(api.get(membersPath() + "?limit=1&cursor=" + first.get("next_cursor").asString(), member));
        assertThat(second.get("has_more").asBoolean()).isFalse();
        assertThat(second.get("items").get(0).get("id").asString())
                .isNotEqualTo(first.get("items").get(0).get("id").asString());

        assertThat(api.get(membersPath(), outsider).statusCode()).isEqualTo(404);
        assertThat(api.get(membersPath(), gone).statusCode()).isEqualTo(404);
    }

    /** 깨지면: 팀원이 남의 표시를 바꾸거나, 잘못된 값이 저장되거나, 이 요청으로 역할이 바뀐다 (MEM-A07, 완료 조건 1). */
    @Test
    void update_leaderOnly_validatesAndClears_withoutTouchingRole() {
        String path = membersPath() + "/" + api.memberId("member@test");

        assertThat(api.patch(path, "{\"domain\":\"backend\"}", member).statusCode()).isEqualTo(403);
        assertThat(api.patch(path, "{}", leader).statusCode()).isEqualTo(400);
        assertThat(api.patch(path, "{\"color\":\"red\"}", leader).statusCode()).isEqualTo(400);
        assertThat(api.patch(path, "{\"domain\":\"" + "가".repeat(51) + "\"}", leader).statusCode()).isEqualTo(400);

        JsonNode updated = read(api.patch(path,
                "{\"domain\":\" backend \",\"role_label\":\"백엔드\",\"color\":\"#ffd54f\"}", leader));
        assertThat(updated.get("domain").asString()).isEqualTo("backend");
        assertThat(updated.get("color").asString()).isEqualTo("#FFD54F");
        assertThat(updated.get("role").asString()).isEqualTo("MEMBER");

        // 생략은 유지, "" 은 지우기
        JsonNode cleared = read(api.patch(path, "{\"role_label\":\"\"}", leader));
        assertThat(cleared.has("role_label")).isFalse();
        assertThat(cleared.get("domain").asString()).isEqualTo("backend");

        fixtures().removeMember(api.memberId("member@test"));
        assertThat(api.patch(path, "{\"domain\":\"x\"}", leader).statusCode()).isEqualTo(404);
    }

    /** 깨지면: 위임 뒤에도 이전 팀장이 팀장 일을 하거나, 팀장이 둘·0명이 된다 (MEM-A08, 완료 조건 2). */
    @Test
    void transfer_swapsRoles_andOldLeaderLosesLeaderRequests() {
        String path = "/api/projects/" + projectId + "/leadership-transfer";
        long leaderMemberId = api.memberId("leader@test");
        long memberMemberId = api.memberId("member@test");
        long gone = joinAsMember();
        fixtures().removeMember(memberIdOf(gone));

        assertThat(api.post(path, body("target_member_id", leaderMemberId), member).statusCode()).isEqualTo(403);
        assertCode(api.post(path, body("target_member_id", leaderMemberId), leader), 409, "invalid_transfer_target");
        assertCode(api.post(path, body("target_member_id", memberIdOf(gone)), leader), 409, "invalid_transfer_target");
        long elsewhere = fixtures().projectWithLeader().leaderMemberId();
        assertThat(api.post(path, body("target_member_id", elsewhere), leader).statusCode()).isEqualTo(404);

        JsonNode res = read(api.post(path, body("target_member_id", memberMemberId), leader));
        assertThat(res.get("members").get(0).get("id").asString()).isEqualTo(String.valueOf(leaderMemberId));
        assertThat(res.get("members").get(0).get("role").asString()).isEqualTo("MEMBER");
        assertThat(res.get("members").get(1).get("role").asString()).isEqualTo("LEADER");

        String projectPath = "/api/projects/" + projectId;
        assertThat(api.patch(projectPath, "{\"name\":\"이전 팀장\"}", leader).statusCode()).isEqualTo(403);
        assertThat(api.post(path, body("target_member_id", leaderMemberId), leader).statusCode()).isEqualTo(403);
        assertThat(api.patch(projectPath, "{\"name\":\"새 팀장\"}", member).statusCode()).isEqualTo(200);
        assertThat(api.jdbc.sql("select count(*) from project_member where project_id = :p and role = 'LEADER' and active")
                .param("p", projectId).query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * 깨지면: 제외·나간 사람의 세션이나 에이전트 토큰이 계속 통하거나, 재초대 규칙(Q05)이 쓸 끝난 이유가 안 남거나,
     * 팀장이 위임 없이 빠져 팀장 없는 프로젝트가 된다 (MEM-A09·A10, 완료 조건 3, -172 합의 4).
     */
    @Test
    void removeAndLeave_blockSessionAndTokens_andRecordReason() {
        long memberToken = api.agentTokenId();
        String projectPath = "/api/projects/" + projectId;

        assertCode(api.post(projectPath + "/leave", null, leader), 409, "leader_transfer_required");
        assertCode(api.post(removePath(api.memberId("leader@test")), "{}", leader), 409, "leader_transfer_required");
        assertThat(api.post(removePath(api.memberId("leader@test")), "{}", member).statusCode()).isEqualTo(403);

        assertThat(api.get(projectPath, member).statusCode()).isEqualTo(200);
        assertThat(api.post(removePath(api.memberId("member@test")), null, leader).statusCode()).isEqualTo(204);
        assertThat(api.get(projectPath, member).statusCode()).isEqualTo(404);
        assertTokenRevoked(member, memberToken);
        assertThat(leftReason(api.memberId("member@test"))).isEqualTo("REMOVED");
        assertThat(api.post(removePath(api.memberId("member@test")), null, leader).statusCode()).isEqualTo(404);
        // 다시 초대받아 수락하면 끝난 이유를 지운다 — 남아 있으면 활성 행 CHECK(V11) 에 걸려 수락이 실패한다
        String url = read(api.post(projectPath + "/invitations", null, leader)).get("invite_url").asString();
        String token = url.substring(url.indexOf("token=") + "token=".length());
        read(api.post("/api/invitations/accept", "{\"token\":\"" + token + "\"}", member));
        assertThat(api.get(projectPath, member).statusCode()).isEqualTo(200);
        assertThat(leftReason(api.memberId("member@test"))).isNull();

        long carol = joinAsMember();
        long carolToken = fixtures().agentToken(projectId, carol);
        assertThat(api.post(projectPath + "/leave", "{}", carol).statusCode()).isEqualTo(204);
        assertThat(api.get(projectPath, carol).statusCode()).isEqualTo(404);
        assertTokenRevoked(carol, carolToken);
        assertThat(leftReason(memberIdOf(carol))).isEqualTo("LEFT");
        // 팀장 토큰은 남는다 — 그 사람 것만 폐기한다
        assertThat(api.jdbc.sql("select revoked_at is null from agent_token where id = :t")
                .param("t", api.leaderAgentTokenId()).query(Boolean.class).single()).isTrue();
    }

    /**
     * 깨지면: 열린 작업을 담당자 없는 사람에게 남긴 채 빠지거나, 넘겨받은 사람이 떠난 사람의 검증(도는 중이던 것 포함)으로
     * 진행 중 작업을 이어 가거나, 끝난 작업까지 담당자가 바뀐다 (완료 조건 4, 04-task-lifecycle.md §6 진행 중 담당자 변경, 2026-10-29 결정).
     */
    @Test
    void openTasks_blockUntilReassigned_inProgressGoesBackToRevalidation() {
        long ready = createTaskAsMember("준비된 작업");
        long running = createTaskAsMember("진행 중 작업");
        long pass = api.passValidation(running);
        report(running, "START", "\"validation_id\":\"" + pass + "\"");
        // 떠나는 사람의 에이전트가 요청해 두고 아직 도는 검증. 인계 뒤에 PASS 로 끝나도 쓸 수 없어야 한다
        long runningValidation = api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        select t.id, t.current_plan_revision_id, p.current_baseline_id, 'RUNNING', 'AGENT', 'snap-run', 'r1'
                          from task t join project p on p.id = t.project_id where t.id = :t
                        returning id
                        """).param("t", running).query(Long.class).single();
        long done = createTaskAsMember("끝난 작업");
        report(done, "START", "\"validation_id\":\"" + api.passValidation(done) + "\"");
        report(done, "COMPLETED",
                "\"summary\":\"끝\",\"delta\":[],\"completion_checks\":[{\"criterion\":\"테스트 통과\",\"met\":true}]");

        long memberMemberId = api.memberId("member@test");
        assertCode(api.post(removePath(memberMemberId), null, leader), 409, "active_tasks_exist");
        assertThat(api.get("/api/projects/" + projectId, member).statusCode()).isEqualTo(200);
        assertThat(api.post(removePath(memberMemberId), body("reassign_to_member_id", memberMemberId), leader)
                .statusCode()).isEqualTo(400);
        long elsewhere = fixtures().projectWithLeader().leaderMemberId();
        assertThat(api.post(removePath(memberMemberId), body("reassign_to_member_id", elsewhere), leader)
                .statusCode()).isEqualTo(400);

        long carol = joinAsMember();
        long carolMemberId = memberIdOf(carol);
        assertThat(api.post(removePath(memberMemberId), body("reassign_to_member_id", carolMemberId), leader)
                .statusCode()).isEqualTo(204);

        assertThat(taskRow(ready)).isEqualTo("READY|null|" + carolMemberId);
        assertThat(taskRow(running)).isEqualTo("WAITING|REVALIDATION|" + carolMemberId);
        assertThat(validationRow(pass)).isEqualTo("STALE|ASSIGNEE_CHANGED");
        assertThat(validationRow(runningValidation)).isEqualTo("STALE|ASSIGNEE_CHANGED");
        assertThat(taskRow(done)).isEqualTo("COMPLETED|null|" + memberMemberId);

        // 나가기도 같은 규칙: 인계 대상을 주면 넘기고 빠진다
        String leavePath = "/api/projects/" + projectId + "/leave";
        assertCode(api.post(leavePath, null, carol), 409, "active_tasks_exist");
        long leaderMemberId = api.memberId("leader@test");
        assertThat(api.post(leavePath, body("reassign_to_member_id", leaderMemberId), carol).statusCode()).isEqualTo(204);
        assertThat(taskRow(ready)).isEqualTo("READY|null|" + leaderMemberId);
        assertThat(taskRow(running)).isEqualTo("WAITING|REVALIDATION|" + leaderMemberId);
    }

    /**
     * 깨지면: 토큰 발급과 제외가 겹칠 때 제외 뒤에도 폐기 안 된 토큰이 남는다 — 두 쪽이 같은 멤버십 행을 잠그는 약속이 빠졌다
     * (-172 합의 4). 겹치는 순간을 매번 만들 수는 없어서, 어느 순서로 끝나도 결과가 같은지를 본다.
     */
    @Test
    void concurrentIssueAndRemove_leavesNoLiveToken() throws Exception {
        String issuePath = "/api/projects/" + projectId + "/agent-tokens";
        String removePath = removePath(api.memberId("member@test"));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Integer> issue = CompletableFuture.supplyAsync(() -> {
                await(start);
                return api.post(issuePath, "{}", member).statusCode();
            }, pool);
            CompletableFuture<Integer> remove = CompletableFuture.supplyAsync(() -> {
                await(start);
                return api.post(removePath, null, leader).statusCode();
            }, pool);
            start.countDown();
            assertThat(remove.join()).isEqualTo(204);
            // 발급이 먼저면 201 뒤 제외가 폐기하고, 제외가 먼저면 이미 멤버가 아니라 404
            assertThat(issue.join()).isIn(201, 404);
        } finally {
            pool.shutdownNow();
        }
        assertThat(api.jdbc.sql("select count(*) from agent_token where project_id = :p and account_id = :a and revoked_at is null")
                .param("p", projectId).param("a", member).query(Long.class).single()).isZero();
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private String membersPath() {
        return "/api/projects/" + projectId + "/members";
    }

    private String removePath(long memberId) {
        return membersPath() + "/" + memberId + "/remove";
    }

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 새 계정을 프로젝트 P 의 MEMBER 로 넣는다 */
    private long joinAsMember() {
        long account = fixtures().account();
        fixtures().member(projectId, account, ProjectRole.MEMBER);
        return account;
    }

    private long memberIdOf(long accountId) {
        return api.jdbc.sql("select id from project_member where project_id = :p and account_id = :a")
                .param("p", projectId).param("a", accountId).query(Long.class).single();
    }

    private String leftReason(long memberId) {
        // 활성 행은 NULL 이라 single() 대신 list 로 읽는다 (single 은 NULL 을 거부한다)
        return api.jdbc.sql("select left_reason from project_member where id = :m").param("m", memberId)
                .query(String.class).list().get(0);
    }

    /** member 가 만든 작업은 member 가 담당한다 (TSK-A01) */
    private long createTaskAsMember(String title) {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson(title, api.readsRef("API-01"), "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return TaskApiSupport.longField(res.body(), "id");
    }

    /** member 가 작업 상태를 보고한다 (REST state-events, 사람 세션). extra 는 이벤트별 필드 */
    private void report(long taskId, String event, String extra) {
        Map<String, Object> row = api.jdbc.sql("select current_plan_revision_id, lock_version from task where id = :t")
                .param("t", taskId).query().singleRow();
        HttpResponse<String> res = api.post(tasksPath() + "/" + taskId + "/state-events",
                "{\"event\":\"" + event + "\",\"plan_revision_id\":\"" + row.get("current_plan_revision_id")
                        + "\",\"expected_lock_version\":" + row.get("lock_version") + "," + extra + "}", member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
    }

    /** 상태|무효 사유 */
    private String validationRow(long validationId) {
        return api.jdbc.sql("select status || '|' || coalesce(stale_reason, 'null') from validation where id = :v")
                .param("v", validationId).query(String.class).single();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** 상태|대기 사유|담당 멤버 */
    private String taskRow(long taskId) {
        return api.jdbc.sql("select status || '|' || coalesce(waiting_reason, 'null') || '|' || assignee_member_id"
                        + " from task where id = :t").param("t", taskId).query(String.class).single();
    }

    private void assertTokenRevoked(long accountId, long tokenId) {
        assertThatThrownBy(() -> access.requireMember(CallerContext.agent(accountId, tokenId), projectId))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("token_revoked"));
    }

    private static void assertCode(HttpResponse<String> res, int status, String code) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        assertThat(res.body()).contains("\"code\":\"" + code + "\"");
    }

    private static String body(String field, long id) {
        return "{\"" + field + "\":\"" + id + "\"}";
    }

    private JsonNode read(HttpResponse<String> res) {
        assertThat(res.statusCode()).as(res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }

    private static List<String> names(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(m -> out.add(m.get("name").asString()));
        return out;
    }
}
