package com.beez.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
     * 진행 중 작업을 이어 가거나, 끝난 작업까지 담당자가 바뀌거나, 담당자와 무관한 DELTA 검증까지 버려져 자동 완료(S4)가 막힌다
     * (완료 조건 4, 04-task-lifecycle.md §6 진행 중 담당자 변경·§2.3, 2026-10-29 결정).
     */
    @Test
    void openTasks_blockUntilReassigned_inProgressGoesBackToRevalidation() {
        long ready = createTaskAsMember("준비된 작업");
        long running = createTaskAsMember("진행 중 작업");
        long pass = api.passValidation(running);
        report(running, "START", "\"validation_id\":\"" + pass + "\"");
        // 떠나는 사람의 에이전트가 요청해 두고 아직 도는 검증. 인계 뒤에 PASS 로 끝나도 쓸 수 없어야 한다
        long runningValidation = insertValidation(running, "RUNNING", "AGENT");
        // REVALIDATION 은 누가 접수하는지(-159) 정해지기 전이라 에이전트 요청일 수 있어 함께 막는다
        long revalidation = insertValidation(running, "RUNNING", "REVALIDATION");
        // delta 완료 보고 뒤 DELTA 검증을 기다리는 작업. DELTA 검증은 담당자와 무관해 인계 뒤에도 살아 있어야 한다
        long awaitingDelta = createTaskAsMember("delta 기다리는 작업");
        long deltaStartPass = api.passValidation(awaitingDelta);
        report(awaitingDelta, "START", "\"validation_id\":\"" + deltaStartPass + "\"");
        report(awaitingDelta, "COMPLETED", "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"응답에 nickname 추가\"}]");
        long deltaValidation = insertValidation(awaitingDelta, "RUNNING", "DELTA");
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
        assertThat(validationRow(revalidation)).isEqualTo("STALE|ASSIGNEE_CHANGED");
        assertThat(taskRow(awaitingDelta)).isEqualTo("WAITING|REVALIDATION|" + carolMemberId);
        assertThat(validationRow(deltaStartPass)).isEqualTo("STALE|ASSIGNEE_CHANGED");
        assertThat(validationRow(deltaValidation)).isEqualTo("RUNNING|null");
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
     * 깨지면: 팀장이 담당자 변경(TSK-A06)으로 진행 중 작업을 넘기면 제외·나가기와 달리 이전 담당자의 검증으로 계속 진행되거나,
     * 같은 담당자로 다시 지정했을 뿐인데 진행 중 작업이 멈춘다 (§6 진행 중 담당자 변경, 두 경로 같은 규칙).
     */
    @Test
    void assigneeChange_onInProgressTask_followsHandoverRule() {
        long running = createTaskAsMember("진행 중 작업");
        long pass = api.passValidation(running);
        report(running, "START", "\"validation_id\":\"" + pass + "\"");
        long memberMemberId = api.memberId("member@test");
        long leaderMemberId = api.memberId("leader@test");

        // 같은 담당자로 다시 지정 → 아무것도 바뀌지 않는다
        assertThat(changeAssignee(running, memberMemberId).statusCode()).isEqualTo(200);
        assertThat(taskRow(running)).isEqualTo("IN_PROGRESS|null|" + memberMemberId);
        assertThat(validationRow(pass)).isEqualTo("PASS|null");

        HttpResponse<String> res = changeAssignee(running, leaderMemberId);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(res.body(), "status")).isEqualTo("WAITING");
        assertThat(taskRow(running)).isEqualTo("WAITING|REVALIDATION|" + leaderMemberId);
        assertThat(validationRow(pass)).isEqualTo("STALE|ASSIGNEE_CHANGED");
    }

    /**
     * 깨지면: 팀장 위임이 커밋되는 동안 프로젝트 잠금을 기다린 팀장 전용 요청이 이미 팀장이 아닌 사람으로 처리된다
     * (MEM-A08 "요청 처리 시점의 팀장 권한 재확인"). 대상은 담당자 변경(TSK-A06)·표시 수정(MEM-A07)·프로젝트 수정·삭제(PRJ-A04·A05).
     * 잠금을 쥔 트랜잭션에서 위임을 흉내 내 커밋해, 겹치는 순서를 매번 똑같이 만든다.
     */
    @Test
    void leaderOnlyRequests_waitingOnLock_areRejectedAfterTransfer() throws Exception {
        long task = createTaskAsMember("담당 변경 대상");
        long leaderMemberId = api.memberId("leader@test");
        long memberMemberId = api.memberId("member@test");
        String projectPath = "/api/projects/" + projectId;
        ApiClient leaderClient = client().login("leader@test", AccessFixtures.PASSWORD);

        assertCode(whileLeadershipMoves(leaderMemberId, memberMemberId,
                () -> changeAssignee(task, leaderMemberId)), 403, "forbidden");
        assertThat(taskRow(task)).endsWith("|" + memberMemberId);
        assertCode(whileLeadershipMoves(leaderMemberId, memberMemberId,
                () -> api.patch(membersPath() + "/" + memberMemberId, "{\"domain\":\"x\"}", leader)), 403, "forbidden");
        assertCode(whileLeadershipMoves(leaderMemberId, memberMemberId,
                () -> api.patch(projectPath, "{\"name\":\"이전 팀장\"}", leader)), 403, "forbidden");
        assertCode(whileLeadershipMoves(leaderMemberId, memberMemberId,
                () -> leaderClient.delete(projectPath, "{\"confirm_name\":\"P\"}")), 403, "forbidden");
        assertThat(api.jdbc.sql("select name || '|' || (archived_at is null) from project where id = :p")
                .param("p", projectId).query(String.class).single()).isEqualTo("P|true");
    }

    /**
     * 깨지면: 토큰 발급과 제외가 겹칠 때 제외 뒤에도 폐기 안 된 토큰이 남는다 — 제외가 토큰을 폐기하기 전에 발급(AGT-A01)과 같은
     * 멤버십 행을 잠그는 약속이 빠졌다 (-172 합의 4). 발급이 그 행을 먼저 잠근 상황을 별도 트랜잭션으로 만든다: 행을 쥔 채 제외를 보내
     * 제외가 그 잠금에서 기다리는지 보고, 같은 트랜잭션에서 토큰을 넣고 커밋한 뒤 제외가 그 토큰까지 폐기하는지 본다.
     * 순서가 고정이라 결과가 매번 같다. (명시적 잠금을 빼도 멤버십 UPDATE 가 폐기보다 먼저면 그 UPDATE 가 같은 잠금을 기다려 통과한다 —
     * 이 시험이 지키는 것은 "폐기는 그 행을 잠근 뒤"다.)
     */
    @Test
    void remove_waitsOnMembershipLock_andRevokesTokenIssuedMeanwhile() throws Exception {
        long memberMemberId = api.memberId("member@test");
        HttpResponse<String> removed = whileRowLocked("select id from project_member where id = ? for update", memberMemberId,
                () -> api.post(removePath(memberMemberId), null, leader),
                lock -> {
                    try (PreparedStatement ps = lock.prepareStatement(
                            "insert into agent_token (project_id, account_id, name, token_hash) values (?, ?, 'issued-meanwhile', 'hash-meanwhile')")) {
                        ps.setLong(1, projectId);
                        ps.setLong(2, member);
                        ps.executeUpdate();
                    }
                });
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
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
        // delta 있는 COMPLETED 는 검증을 접수해 202 (TSK-A07)
        assertThat(res.statusCode()).as(res.body()).isIn(200, 202);
    }

    /** 팀장의 담당자 변경 (TSK-A06) */
    private HttpResponse<String> changeAssignee(long taskId, long assigneeMemberId) {
        long lock = api.jdbc.sql("select lock_version from task where id = :t").param("t", taskId).query(Long.class).single();
        return api.patch(tasksPath() + "/" + taskId + "/assignee",
                "{\"assignee_member_id\":\"" + assigneeMemberId + "\",\"expected_lock_version\":" + lock + "}", leader);
    }

    /** 검증 워커(-159) 전이라 SQL 로 현재 계획에 대한 검증 행을 만든다 */
    private long insertValidation(long taskId, String status, String trigger) {
        return api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version)
                        select t.id, t.current_plan_revision_id, p.current_baseline_id, :s, :tr, 'snap-' || :s || :tr, 'r1'
                          from task t join project p on p.id = t.project_id where t.id = :t
                        returning id
                        """).param("t", taskId).param("s", status).param("tr", trigger).query(Long.class).single();
    }

    /** 상태|무효 사유 */
    private String validationRow(long validationId) {
        return api.jdbc.sql("select status || '|' || coalesce(stale_reason, 'null') from validation where id = :v")
                .param("v", validationId).query(String.class).single();
    }

    /**
     * 프로젝트 행을 쥔 채 request 를 보내고, 그동안 팀장을 from → to 로 바꿔 커밋한다(위임 MEM-A08 흉내).
     * request 의 응답을 돌려주고 역할은 원래대로 되돌린다.
     */
    private HttpResponse<String> whileLeadershipMoves(long from, long to, Supplier<HttpResponse<String>> request)
            throws Exception {
        HttpResponse<String> res = whileRowLocked("select id from project where id = ? for update", projectId, request,
                lock -> {
                    // 활성 팀장은 하나(ux_member_one_leader)라 내리는 쪽이 먼저다
                    try (PreparedStatement ps = lock.prepareStatement("update project_member set role = ? where id = ?")) {
                        ps.setString(1, "MEMBER");
                        ps.setLong(2, from);
                        ps.executeUpdate();
                        ps.setString(1, "LEADER");
                        ps.setLong(2, to);
                        ps.executeUpdate();
                    }
                });
        api.jdbc.sql("update project_member set role = 'MEMBER' where id = :m").param("m", to).update();
        api.jdbc.sql("update project_member set role = 'LEADER' where id = :m").param("m", from).update();
        return res;
    }

    /** 행을 쥔 트랜잭션 안에서 할 일 */
    @FunctionalInterface
    private interface WhileLocked {
        void run(Connection lock) throws SQLException;
    }

    /**
     * 별도 트랜잭션에서 lockSql(… for update, 인자 하나)로 행을 쥔 채 request 를 보낸다. request 가 그 잠금에서 기다리는 것을 확인한 뒤
     * 같은 트랜잭션에서 whileLocked 를 하고 커밋해, request 가 그 결과를 본 상태로 이어 가게 한다. 겹치는 순서를 매번 똑같이 만든다.
     */
    private HttpResponse<String> whileRowLocked(String lockSql, long id, Supplier<HttpResponse<String>> request,
                                                WhileLocked whileLocked) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement ps = lock.prepareStatement(lockSql)) {
                ps.setLong(1, id);
                ps.executeQuery();
            }
            Future<HttpResponse<String>> pending = pool.submit(request::get);
            awaitBlockedOnLock();
            whileLocked.run(lock);
            lock.commit();
            return pending.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
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
