package com.beez.backend.acceptance;

import static com.beez.backend.support.McpTestClient.call;
import static com.beez.backend.support.McpTestClient.error;
import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.McpTestClient;
import com.beez.backend.task.TaskApiSupport;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 인수 기준 AC-11·AC-14 와 MCP 접근 회귀 (Jira -208 작업 2·3·4).
 *
 * <p>이미 다른 시험이 덮는 곳은 다시 쓰지 않는다 — 담당자 변경이 진행 중 작업을 WAITING(REVALIDATION)·검증 STALE 로 내리는 것
 * (ProjectMemberApi assigneeChange_onInProgressTask_followsHandoverRule), 팀장 취소는 보고가 끊긴 IN_PROGRESS 만·팀장 에이전트는 불가
 * (TaskStateApi cancel_leaderOnlyWhenStalled_agentOfLeaderNever — 서비스 직접 호출), WAITING 은 끊긴 작업이 아님
 * (cancel_waitingTaskIsNotStalled_evenAfterThreshold), 폐기 토큰·끝난 멤버십·남의 세션·밀려난 세션
 * (AgentConnection·AgentTokenApi·ProjectAccess·McpServer 시험). 여기서는 그 사이의 빈 곳만 본다:
 * <ul>
 *   <li>AC-11: 인계받은 <b>새 에이전트가 MCP 로만</b> 검증 상태·이유, 계획 리비전·승인 버전, 공유 판단·미해결 항목을 읽고,
 *       옛 검증으로는 이어 가지 못하는지</li>
 *   <li>AC-14: 보고가 끊긴 작업이 시간만으로 풀리지 않고(상태·검증·점유 그대로) 사람 팀장의 명시적 취소로만 풀리는지,
 *       MCP 를 거친 팀장 에이전트의 취소가 막히는지</li>
 *   <li>끝난 세션(닫힘)과 서버가 잊은 세션(유휴 정리·재배포)이 404 로 다시 연결하게 하는지</li>
 * </ul>
 */
class AccessAcceptanceAc11Ac14IntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    TaskApiSupport api;
    long projectId;
    Long member;
    Long leader;
    String memberToken;
    String leaderToken;
    /** tearDown 에서 닫을 MCP 연결 (AccessAcceptanceAc10IntegrationTests 와 같은 이유) */
    final List<McpSyncClient> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
        leader = api.accountId("leader@test");
        // 픽스처 토큰에 원문을 정해 둔다 (McpToolsIntegrationTests 와 같은 방식)
        memberToken = SecretTokens.generate();
        leaderToken = SecretTokens.generate();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(memberToken)).param("id", api.agentTokenId()).update();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(leaderToken)).param("id", api.leaderAgentTokenId()).update();
    }

    @AfterEach
    void tearDown() {
        // 종료 DELETE 를 기다리지 않으면 다음 시험의 truncate 와 교착한다. 하나가 실패해도 나머지는 닫는다
        RuntimeException failure = null;
        for (McpSyncClient client : opened) {
            try {
                client.closeGracefully();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ---- AC-11 ----

    /**
     * 깨지면: 진행 중 작업을 넘겨받은 새 에이전트가 MCP 만으로는 이전 검증이 왜 무효인지·계획이 어느 승인 버전을 참조했는지·이전 담당자가
     * 남긴 공유 판단과 미해결 항목을 알 수 없거나(이전 담당자의 비공개 기록이 섞이거나), 현재 계획과 잠금 번호를 읽어 다시 검증·RESUME 하지
     * 못하거나(-335), 옛 PASS 로 바로 이어 가거나,
     * 이전 담당자의 에이전트가 계속 보고한다 (AC-11, 04-task-lifecycle §6 진행 중 담당자 변경 S5).
     */
    @Test
    void ac11_newAgentAfterHandover_readsWhatItNeedsOverMcp_andMustRevalidateBeforeResuming() {
        long taskId = createTask(member, "AC11 결제 연동", api.readsRef("API-01"));
        long revisionId = currentRevision(taskId);
        long oldPass = api.passValidation(taskId);
        assertThat(report(taskId, "START", revisionId, oldPass, member).statusCode()).isEqualTo(200);

        // 이전 담당자의 에이전트가 작업에 남긴 기록: 공유 판단·미해결 항목(공유), 개인 메모(비공개)
        McpSyncClient memberMcp = mcp(memberToken);
        String decision = write(memberMcp, taskId, "DECISION", "SHARED", "AC11 재시도는 3회까지");
        String issue = write(memberMcp, taskId, "ISSUE", "SHARED", "AC11 환불 경로 미정");
        String privateNote = write(memberMcp, taskId, "DECISION", "PRIVATE", "AC11 개인 메모");

        // 팀장이 진행 중 작업을 자기에게 넘긴다 (TSK-A06) — 새 에이전트 = 팀장의 에이전트
        HttpResponse<String> moved = api.patch(tasksPath() + "/" + taskId + "/assignee",
                "{\"assignee_member_id\":\"" + api.memberId("leader@test") + "\",\"expected_lock_version\":"
                        + lockVersion(taskId) + "}", leader);
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        McpSyncClient leaderMcp = mcp(leaderToken);

        // 1) 검증 상태와 무효가 된 이유 (MCP-03 validation_validity)
        JsonNode changes = ok(call(leaderMcp, "get_design_changes",
                Map.of("since_version", 0, "task_id", String.valueOf(taskId))));
        JsonNode validity = changes.get("validation_validity");
        assertThat(validity).hasSize(1);
        assertThat(validity.get(0).get("validation_id").asString()).isEqualTo(String.valueOf(oldPass));
        assertThat(validity.get(0).get("status").asString()).isEqualTo("STALE");
        assertThat(validity.get(0).get("reason").asString()).isEqualTo("ASSIGNEE_CHANGED");

        // 2) 계획 리비전과 참조한 승인 버전 — 지금 기준과 같은지 대조할 수 있다 (MCP-13)
        JsonNode validation = ok(call(leaderMcp, "get_validation", Map.of("validation_id", String.valueOf(oldPass))));
        assertThat(validation.get("plan_revision_id").asString()).isEqualTo(String.valueOf(revisionId));
        assertThat(validation.get("baseline_id").asString())
                .isEqualTo(changes.get("current_baseline").get("id").asString());
        assertThat(validation.get("design_refs")).extracting(r -> r.get("design_item_version_id").asString())
                .containsExactly(String.valueOf(api.currentVersionId("API-01")));

        // 3) 공유 판단·미해결 항목은 보이고 이전 담당자의 비공개 기록은 안 보인다 (MCP-05)
        JsonNode shared = ok(call(leaderMcp, "find_context", Map.of("query", "AC11", "task_id", String.valueOf(taskId))));
        assertThat(shared.get("items")).extracting(n -> n.get("id").asString())
                .containsExactlyInAnyOrder(decision, issue);
        // 대조군: 쓴 사람에게는 비공개 기록도 같은 검색으로 나온다 — 위에서 빠진 것이 검색어 탓이 아니다
        JsonNode own = ok(call(memberMcp, "find_context", Map.of("query", "AC11", "task_id", String.valueOf(taskId))));
        assertThat(own.get("items")).extracting(n -> n.get("id").asString())
                .containsExactlyInAnyOrder(decision, issue, privateNote);

        // 4) 현재 계획 본문과 잠금 번호도 MCP 로 읽는다 (MCP-16 get_task, -335). 인계로 잠금 번호가 올라 처음 값으로는 못 쓴다
        JsonNode task = ok(call(leaderMcp, "get_task", Map.of("task_id", String.valueOf(taskId))));
        assertThat(task.get("status").asString()).isEqualTo("WAITING");
        assertThat(task.get("waiting_reason").asString()).isEqualTo("REVALIDATION");
        assertThat(task.get("current_plan_revision").get("id").asString()).isEqualTo(String.valueOf(revisionId));
        assertThat(task.get("current_plan_revision").get("plan").get("title").asString()).isEqualTo("AC11 결제 연동");
        long lock = task.get("lock_version").asLong();

        // 5) 최신성 확인: 옛 PASS 로는 이어 가지 못한다. 이전 담당자의 에이전트는 이 작업을 더 보고하지 못한다.
        //    잠금 번호가 맞으므로 거부 이유는 revision_conflict 가 아니라 검증이다
        assertThat(error(resume(leaderMcp, taskId, revisionId, lock, oldPass, "k-r1")))
                .containsEntry("code", "validation_not_passed");
        assertThat(error(resume(memberMcp, taskId, revisionId, lock, oldPass, "k-r2"))).containsEntry("code", "forbidden");

        // 6) 대조군: 새 에이전트가 get_task 로 읽은 값으로 다시 검증을 요청하고(MCP-11), PASS 가 나면 같은 호출로 이어 간다
        JsonNode revalidation = ok(call(leaderMcp, "validate_task_plan", Map.of("task_id", String.valueOf(taskId),
                "plan_revision_id", task.get("current_plan_revision").get("id").asString(),
                "expected_lock_version", lock, "idempotency_key", "k-v1")));
        // 시험 설정(정책 분석 꺼짐)에서는 규칙 검사만으로 결정돼 접수 때 끝난다 — 워커를 돌리지 않아도 된다
        JsonNode passed = ok(call(leaderMcp, "get_validation", Map.of("validation_id", revalidation.get("id").asString())));
        assertThat(passed.get("status").asString()).as(passed.toString()).isEqualTo("PASS");
        long newPass = passed.get("id").asLong();
        JsonNode resumed = ok(resume(leaderMcp, taskId, revisionId, lock, newPass, "k-r3"));
        assertThat(resumed.get("task").get("status").asString()).isEqualTo("IN_PROGRESS");
    }

    // ---- AC-14 ----

    /**
     * 깨지면: 보고가 끊긴 작업이 시간만 지나 상태·검증이 바뀌거나 점유를 놓아 같은 설계 항목을 바꾸는 다른 작업이 시작되거나(자동 완료·해제),
     * MCP 어댑터가 팀장의 에이전트 호출을 사람 호출처럼 넘겨 에이전트가 끊긴 작업을 취소한다 (AC-14, 04-task-lifecycle §4.1·§5).
     */
    @Test
    void ac14_stalledTask_keepsStateAndOccupancy_untilHumanLeaderCancels_notLeadersAgent() {
        long stalled = createTask(member, "AC14 끊긴 작업", api.changesRef("API-01"));
        long stalledRevision = currentRevision(stalled);
        long stalledPass = api.passValidation(stalled);
        assertThat(report(stalled, "START", stalledRevision, stalledPass, member).statusCode()).isEqualTo(200);
        // 마지막 보고를 임계값(기본 30분) 넘게 전으로 돌린다
        api.jdbc.sql("update task set updated_at = now() - interval '1 hour' where id = :t").param("t", stalled).update();

        // 시간만으로는 아무것도 바뀌지 않는다
        JsonNode detail = taskDetail(stalled);
        assertThat(detail.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(detail.get("validation_status").asString()).isEqualTo("PASS");

        // 점유도 그대로다 — 같은 항목을 바꾸는 다른 작업은 시작하지 못한다 (G4)
        long other = createTask(leader, "AC14 같은 항목", api.changesRef("API-01"));
        long otherRevision = currentRevision(other);
        long otherPass = api.passValidation(other);
        HttpResponse<String> blocked = report(other, "START", otherRevision, otherPass, leader);
        assertThat(blocked.statusCode()).isEqualTo(409);
        assertThat(blocked.body()).contains("\"code\":\"task_conflict\"");

        // 팀장의 에이전트가 MCP 로 취소 → 사람이 아니므로 거부, 작업은 그대로
        McpSyncClient leaderMcp = mcp(leaderToken);
        assertThat(error(call(leaderMcp, "report_task_state", Map.of("task_id", String.valueOf(stalled), "event", "CANCEL",
                "plan_revision_id", String.valueOf(stalledRevision), "expected_lock_version", lockVersion(stalled),
                "idempotency_key", "k-cancel")))).containsEntry("code", "forbidden");
        assertThat(taskDetail(stalled).get("status").asString()).isEqualTo("IN_PROGRESS");

        // 대조군: 사람 팀장은 같은 작업을 취소한다. 그 뒤에야 다른 작업이 시작된다
        HttpResponse<String> cancelled = api.post(tasksPath() + "/" + stalled + "/state-events",
                stateJson("CANCEL", stalledRevision, lockVersion(stalled), null), leader);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        HttpResponse<String> started = report(other, "START", otherRevision, otherPass, leader);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(started.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    /**
     * 깨지면: 에이전트가 MCP 로 계속 일하는데(요청마다 연결의 마지막 통신이 갱신됨) 상태 전이 없이 30분이 지나면 그 작업이 "보고가 끊긴 작업"
     * 으로 판정돼 팀장이 되돌릴 수 없는 취소를 할 수 있거나, 에이전트 작업 현황(MON-A05)의 작업 칸이 늘 빈다 — START 가 그 연결에
     * 현재 작업을 적지 않아 끊김 판정이 작업의 마지막 변경 시각만 본다 (AC-14, -336, 04-task-lifecycle §4.1).
     */
    @Test
    void ac14_agentWorkingOverMcp_isNotStalled_whileItsConnectionKeepsTalking() {
        long taskId = createTask(member, "AC14 MCP 로 일하는 작업", api.changesRef("API-01"));
        long revisionId = currentRevision(taskId);
        long pass = api.passValidation(taskId);
        McpSyncClient memberMcp = mcp(memberToken);
        JsonNode started = ok(start(memberMcp, taskId, revisionId, pass, "k-s1"));
        assertThat(started.get("task").get("status").asString()).isEqualTo("IN_PROGRESS");
        long sessionId = liveSessionOf(api.agentTokenId());

        // START 한 연결에 현재 작업이 적히고, 에이전트 작업 현황의 작업 칸에 나온다
        assertThat(currentTaskOf(sessionId)).isEqualTo(taskId);
        HttpResponse<String> work = api.get("/api/projects/" + projectId + "/agents/work-status", leader);
        assertThat(work.statusCode()).as(work.body()).isEqualTo(200);
        JsonNode items = json.readTree(work.body()).get("items");
        assertThat(items).filteredOn(n -> n.get("agent_session_id").asLong() == sessionId)
                .singleElement()
                .satisfies(n -> assertThat(n.get("current_task_id").asLong()).isEqualTo(taskId));

        // 작업은 1시간 동안 바뀌지 않았지만 연결은 방금 통신했다 → 끊긴 작업이 아니라 팀장이 취소하지 못한다
        api.jdbc.sql("update task set updated_at = now() - interval '1 hour' where id = :t").param("t", taskId).update();
        HttpResponse<String> refused = report(taskId, "CANCEL", revisionId, null, leader);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(taskDetail(taskId).get("status").asString()).isEqualTo("IN_PROGRESS");

        // 대조군: 연결의 통신도 1시간 전에 멈췄으면 끊긴 작업이라 팀장이 취소한다 — 위 거부가 다른 이유가 아니다
        api.jdbc.sql("update agent_session set last_report_at = now() - interval '1 hour' where id = :s")
                .param("s", sessionId).update();
        HttpResponse<String> cancelled = report(taskId, "CANCEL", revisionId, null, leader);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(cancelled.body(), "status")).isEqualTo("CANCELLED");
    }

    /**
     * 깨지면: 담당자가 바뀐 뒤에도 이전 담당자의 연결이 그 작업을 현재 작업으로 가리킨 채 통신해, 새 담당자의 보고가 끊겨도 팀장이 취소하지
     * 못한다 (AC-14, -336). 끊김 판정은 지금 담당자의 연결만 센다.
     */
    @Test
    void ac14_previousAssigneesConnection_doesNotHideStall_afterHandover() {
        // 팀장 에이전트가 시작한 작업을 member 에게 넘기고, member 에이전트가 다시 검증 PASS 로 이어 간다
        long taskId = createTask(leader, "AC14 넘겨받은 작업", api.changesRef("API-01"));
        long revisionId = currentRevision(taskId);
        McpSyncClient leaderMcp = mcp(leaderToken);
        ok(start(leaderMcp, taskId, revisionId, api.passValidation(taskId), "k-s2"));
        long leaderSession = liveSessionOf(api.leaderAgentTokenId());
        assertThat(currentTaskOf(leaderSession)).isEqualTo(taskId);

        HttpResponse<String> moved = api.patch(tasksPath() + "/" + taskId + "/assignee",
                "{\"assignee_member_id\":\"" + api.memberId("member@test") + "\",\"expected_lock_version\":"
                        + lockVersion(taskId) + "}", leader);
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);
        McpSyncClient memberMcp = mcp(memberToken);
        JsonNode resumed = ok(resume(memberMcp, taskId, revisionId, lockVersion(taskId), api.passValidation(taskId), "k-r4"));
        assertThat(resumed.get("task").get("status").asString()).isEqualTo("IN_PROGRESS");
        long memberSession = liveSessionOf(api.agentTokenId());
        assertThat(currentTaskOf(memberSession)).isEqualTo(taskId);

        // 작업은 1시간 전, 지금 담당자(member)의 연결은 방금 통신 → 끊긴 작업이 아니다
        api.jdbc.sql("update task set updated_at = now() - interval '1 hour' where id = :t").param("t", taskId).update();
        assertThat(report(taskId, "CANCEL", revisionId, null, leader).statusCode()).isEqualTo(403);

        // member 의 연결도 1시간 전에 멈춤. 이전 담당자(팀장)의 연결은 아직 이 작업을 가리킨 채 방금 통신했지만 세지 않는다 → 취소된다
        api.jdbc.sql("update agent_session set last_report_at = now() - interval '1 hour' where id = :s")
                .param("s", memberSession).update();
        api.jdbc.sql("update agent_session set last_report_at = now() where id = :s").param("s", leaderSession).update();
        assertThat(currentTaskOf(leaderSession)).isEqualTo(taskId);
        HttpResponse<String> cancelled = report(taskId, "CANCEL", revisionId, null, leader);
        assertThat(cancelled.statusCode()).as(cancelled.body()).isEqualTo(200);
    }

    /**
     * 깨지면: 에이전트를 다시 실행하면(Claude Code 는 실행마다 새로 연결하고, 진행 중 작업에 START 를 다시 보내지 않는다) 새 연결에 현재 작업이
     * 없어 30분 뒤 다시 팀장 취소가 열리거나, 끝난·남에게 넘어간 작업을 새 연결이 가리킨다 (AC-14, -336 리뷰).
     */
    @Test
    void ac14_reconnectedAgent_keepsItsCurrentTask_soStallIsJudgedOnTheNewConnection() {
        long taskId = createTask(member, "AC14 재시작한 에이전트", api.changesRef("API-01"));
        long revisionId = currentRevision(taskId);
        // 첫 연결에서 시작하고 닫는다 (CLOSED)
        McpSyncClient first = McpTestClient.connect(port(), memberToken);
        first.initialize();
        ok(start(first, taskId, revisionId, api.passValidation(taskId), "k-s3"));
        long firstSession = liveSessionOf(api.agentTokenId());
        first.closeGracefully();

        // 닫힌 연결 뒤 새 연결 → 이어 받음. 그 연결을 닫지 않고 또 연결(밀려남, REPLACED) → 다시 이어 받음
        mcp(memberToken);
        long secondSession = liveSessionOf(api.agentTokenId());
        assertThat(currentTaskOf(secondSession)).isEqualTo(taskId);
        mcp(memberToken);
        long thirdSession = liveSessionOf(api.agentTokenId());
        assertThat(thirdSession).isNotEqualTo(secondSession);
        assertThat(currentTaskOf(thirdSession)).isEqualTo(taskId);

        // 작업과 이전 연결들은 1시간 전, 새 연결은 방금 통신 → 끊긴 작업이 아니다
        api.jdbc.sql("update task set updated_at = now() - interval '1 hour' where id = :t").param("t", taskId).update();
        api.jdbc.sql("update agent_session set last_report_at = now() - interval '1 hour' where id in (:a, :b)")
                .param("a", firstSession).param("b", secondSession).update();
        assertThat(report(taskId, "CANCEL", revisionId, null, leader).statusCode()).isEqualTo(403);

        // 대조군: 새 연결도 1시간 전에 멈췄으면 취소된다
        api.jdbc.sql("update agent_session set last_report_at = now() - interval '1 hour' where id = :s")
                .param("s", thirdSession).update();
        assertThat(report(taskId, "CANCEL", revisionId, null, leader).statusCode()).isEqualTo(200);

        // 끝난(취소된) 작업은 다음 연결이 이어 받지 않는다
        mcp(memberToken);
        assertThat(currentTaskOf(liveSessionOf(api.agentTokenId()))).isNull();
    }

    // ---- 끝난·잊힌 세션 회귀 ----

    /**
     * 깨지면: 클라이언트가 닫은 세션이나 서버가 잊은 세션(유휴 정리·재배포 재시작 — 기록은 끝나지 않은 채 남는다)으로 보낸 요청이
     * 404 가 아닌 응답(200·500 등)을 받아, 클라이언트가 MCP 명세대로 다시 연결하지 못하거나 끝난 세션으로 계속 호출한다 (02-mcp-tools 연결과 인증).
     */
    @Test
    void endedOrForgottenSession_is404_soTheClientReconnects() {
        McpSyncClient first = McpTestClient.connect(port(), memberToken);
        first.initialize();
        String closedSession = ok(call(first, "check_connection", Map.of())).get("session_id").asString();
        first.closeGracefully();

        String forgottenSession = UUID.randomUUID().toString();
        api.jdbc.sql("insert into agent_session (agent_token_id, session_key) values (:t, :k)")
                .param("t", api.agentTokenId()).param("k", forgottenSession).update();

        ApiClient raw = client();
        HttpResponse<String> closed = rawCall(raw, closedSession);
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(404);
        assertThat(json.readTree(closed.body()).get("error").get("code").asString()).isEqualTo("not_found");
        HttpResponse<String> forgotten = rawCall(raw, forgottenSession);
        assertThat(forgotten.statusCode()).as(forgotten.body()).isEqualTo(404);

        // 대조군: 같은 토큰으로 다시 연결하면 쓴다 — 위 404 가 토큰 탓이 아니다
        McpSyncClient again = mcp(memberToken);
        assertThat(ok(call(again, "check_connection", Map.of())).get("connected").asBoolean()).isTrue();
    }

    // ---- 도우미 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    private long createTask(Long account, String title, String refsJson) {
        HttpResponse<String> created = api.post(tasksPath(), api.planJson(title, refsJson, "[]"), account);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return TaskApiSupport.longField(created.body(), "id");
    }

    private long currentRevision(long taskId) {
        return api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
    }

    private int lockVersion(long taskId) {
        return api.jdbc.sql("select lock_version from task where id = :t").param("t", taskId).query(Integer.class).single();
    }

    private JsonNode taskDetail(long taskId) {
        HttpResponse<String> res = api.get(tasksPath() + "/" + taskId, member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    /** REST 상태 보고 (TSK-A07). 잠금 번호는 지금 값 */
    private HttpResponse<String> report(long taskId, String event, long revisionId, Long validationId, Long account) {
        return api.post(tasksPath() + "/" + taskId + "/state-events",
                stateJson(event, revisionId, lockVersion(taskId), validationId), account);
    }

    private static String stateJson(String event, long revisionId, int lock, Long validationId) {
        String validation = validationId == null ? "" : ",\"validation_id\":\"" + validationId + "\"";
        return "{\"event\":\"" + event + "\",\"plan_revision_id\":\"" + revisionId + "\",\"expected_lock_version\":" + lock
                + validation + "}";
    }

    private CallToolResult resume(McpSyncClient agent, long taskId, long revisionId, long lock, long validationId,
                                  String key) {
        return call(agent, "report_task_state", Map.of("task_id", String.valueOf(taskId), "event", "RESUME",
                "plan_revision_id", String.valueOf(revisionId), "expected_lock_version", lock,
                "validation_id", String.valueOf(validationId), "idempotency_key", key));
    }

    private CallToolResult start(McpSyncClient agent, long taskId, long revisionId, long validationId, String key) {
        return call(agent, "report_task_state", Map.of("task_id", String.valueOf(taskId), "event", "START",
                "plan_revision_id", String.valueOf(revisionId), "expected_lock_version", lockVersion(taskId),
                "validation_id", String.valueOf(validationId), "idempotency_key", key));
    }

    /** 이 토큰의 살아 있는 연결 기록 (1인 1에이전트라 하나다) */
    private long liveSessionOf(Long tokenId) {
        return api.jdbc.sql("select id from agent_session where agent_token_id = :k and ended_at is null")
                .param("k", tokenId).query(Long.class).single();
    }

    /** 연결이 가리키는 현재 작업. 없으면 null (single() 은 null 값을 받지 않아 행으로 읽는다) */
    private Long currentTaskOf(long sessionId) {
        return api.jdbc.sql("select current_task_id from agent_session where id = :s").param("s", sessionId)
                .query((rs, i) -> rs.getObject(1, Long.class)).list().get(0);
    }

    private String write(McpSyncClient agent, long taskId, String kind, String visibility, String summary) {
        Map<String, Object> args = new HashMap<>();
        args.put("task_id", String.valueOf(taskId));
        args.put("kind", kind);
        args.put("visibility", visibility);
        args.put("summary", summary);
        args.put("detail", summary + " 상세");
        args.put("design_refs", List.of());
        args.put("idempotency_key", "k-" + summary.hashCode());
        return ok(call(agent, "write_context", args)).get("id").asString();
    }

    /** 세션 ID 를 직접 붙인 tools/call (AgentConnectionIntegrationTests 와 같은 모양) */
    private HttpResponse<String> rawCall(ApiClient raw, String sessionId) {
        String body = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"check_connection","arguments":{}}}""";
        return raw.post("/mcp", body, "Accept", "application/json, text/event-stream",
                "Authorization", "Bearer " + memberToken, "Mcp-Session-Id", sessionId);
    }

    private int port() {
        return Integer.parseInt(env.getRequiredProperty("local.server.port"));
    }

    /** 연결하고 tearDown 에서 닫도록 등록한다 */
    private McpSyncClient mcp(String token) {
        McpSyncClient client = McpTestClient.connect(port(), token);
        opened.add(client);
        client.initialize();
        return client;
    }

    private JsonNode ok(CallToolResult result) {
        return McpTestClient.ok(json, result);
    }
}
