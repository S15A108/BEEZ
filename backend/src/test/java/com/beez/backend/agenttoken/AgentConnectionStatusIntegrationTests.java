package com.beez.backend.agenttoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 에이전트 연결 판정 (S15P31A108-75). 연결 현황 AGT-A05, 작업 현황 MON-A05, 팀원 목록 agent_connected 가 같은 기준
 * ({@link AgentConnectionRule}: 끝나지 않았고 2시간 안에 통신)으로 연결을 보는지, 확인 필요가 IN_PROGRESS 작업을 맡은
 * 멤버에만 서는지 본다. Claude Code 는 /exit 때 DELETE 를 보내지 않아(11-09 실측) 끝낸 세션도 ended_at 이 비어 남는다 —
 * 시간 창이 그 세션을 걸러야 한다.
 */
class AgentConnectionStatusIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

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

    /** 세션 하나. lastReportAgo 는 Postgres interval (예: '3 hours'). 끝남(ended_at)은 비워 둔다 — DELETE 없이 꺼진 클라이언트 */
    private long session(Long tokenId, String key, String lastReportAgo) {
        return api.jdbc.sql("""
                        insert into agent_session (agent_token_id, session_key, last_report_at)
                        values (:k, :key, now() - cast(:ago as interval)) returning id
                        """).param("k", tokenId).param("key", key).param("ago", lastReportAgo).query(Long.class).single();
    }

    /** 다시 연결: 운영처럼 같은 사람의 살아 있는 세션을 REPLACED 로 끝내고(replaceLiveOfMember) 새 세션을 만든다 */
    private long reconnect(Long tokenId, long oldSession, String key, String lastReportAgo) {
        end(oldSession, "REPLACED");
        return session(tokenId, key, lastReportAgo);
    }

    private void end(long sessionId, String reason) {
        api.jdbc.sql("update agent_session set ended_at = now(), end_reason = :r where id = :id and ended_at is null")
                .param("r", reason).param("id", sessionId).update();
    }

    /** member 가 맡은 IN_PROGRESS 작업. 상태 전이 규칙은 이 시험의 관심이 아니라 SQL 로 (IN_PROGRESS 는 시작 검증이 있어야 한다 — task CHECK) */
    private void inProgressTaskOfMember() {
        HttpResponse<String> res = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson("진행 중", api.readsRef("API-01"), "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long validationId = api.passValidation(taskId);
        api.jdbc.sql("""
                        update task set status = 'IN_PROGRESS', started_validation_id = :v, started_at = now(), assignee_member_id = :m
                         where id = :t""")
                .param("v", validationId).param("m", api.memberId("member@test")).param("t", taskId).update();
    }

    private JsonNode get(String path, Long account, int status) throws Exception {
        HttpResponse<String> res = api.get(path, account);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return JSON.readTree(res.body());
    }

    private Map<String, JsonNode> byMember(JsonNode items) {
        Map<String, JsonNode> out = new HashMap<>();
        for (JsonNode n : items) {
            out.put(n.get("member_id").asText(), n);
        }
        return out;
    }

    private Map<String, Boolean> memberListConnected() throws Exception {
        Map<String, Boolean> out = new HashMap<>();
        for (JsonNode n : get("/api/projects/" + projectId + "/members", leader, 200).get("items")) {
            out.put(n.get("id").asText(), n.get("agent_connected").asBoolean());
        }
        return out;
    }

    /**
     * 깨지면: 꺼진 에이전트를 어느 화면은 연결됨으로, 어느 화면은 끊김으로 그린다. 또는 할 일 없이 쉬는 에이전트까지
     * 30분마다 '연결 미확인'이 된다.
     */
    @Test
    void quietSessionWithoutDelete_isNotConnected_onAllThreeViews_andCheckRequiredOnlyWithWorkInProgress() throws Exception {
        String base = "/api/projects/" + projectId;
        String memberId = String.valueOf(api.memberId("member@test"));
        String leaderId = String.valueOf(api.memberId("leader@test"));
        long quiet = session(api.agentTokenId(), "member-quiet", "3 hours");          // /exit 뒤 DELETE 없이 3시간
        long idle = session(api.leaderAgentTokenId(), "leader-idle", "40 minutes");   // 연결은 살아 있고 할 일이 없어 조용

        // AGT-A05: 멤버마다 최근 세션 한 줄. 조용한 세션은 끊김, 쉬는 세션은 연결됨 — 둘 다 확인 필요 아님
        Map<String, JsonNode> a05 = byMember(get(base + "/agents", member, 200).get("items"));
        assertThat(a05).containsOnlyKeys(memberId, leaderId);
        assertThat(a05.get(memberId).get("id").asLong()).isEqualTo(quiet);
        assertThat(a05.get(memberId).get("connected").asBoolean()).isFalse();
        assertThat(a05.get(memberId).get("status_check_required").asBoolean()).isFalse();
        assertThat(a05.get(memberId).has("last_report_at")).isTrue();                   // 끊겨도 마지막 통신 시각은 보인다
        assertThat(a05.get(leaderId).get("id").asLong()).isEqualTo(idle);
        assertThat(a05.get(leaderId).get("connected").asBoolean()).isTrue();
        assertThat(a05.get(leaderId).get("status_check_required").asBoolean()).isFalse();
        assertThat(a05.get(leaderId).has("last_error_code")).isFalse();

        // MON-A05: 살아 있는 세션만 (예전에는 ended_at 만 봐서 3시간 조용한 세션도 나왔다)
        JsonNode work = get(base + "/agents/work-status", member, 200).get("items");
        assertThat(work).hasSize(1);
        assertThat(work.get(0).get("agent_session_id").asLong()).isEqualTo(idle);

        // 팀원 목록: 같은 판정
        assertThat(memberListConnected()).containsEntry(memberId, false).containsEntry(leaderId, true);

        // IN_PROGRESS 작업을 맡고 다시 연결(이전 세션은 REPLACED)한 뒤 40분 조용 → 연결됨 + 확인 필요
        inProgressTaskOfMember();
        long fresh = reconnect(api.agentTokenId(), quiet, "member-fresh", "40 minutes");
        JsonNode stalled = byMember(get(base + "/agents", member, 200).get("items")).get(memberId);
        assertThat(stalled.get("id").asLong()).isEqualTo(fresh);
        assertThat(stalled.get("connected").asBoolean()).isTrue();
        assertThat(stalled.get("status_check_required").asBoolean()).isTrue();
        assertThat(get(base + "/agents/work-status?member_id=" + memberId, member, 200).get("items").get(0)
                .get("agent_session_id").asLong()).isEqualTo(fresh);
        assertThat(memberListConnected()).containsEntry(memberId, true);

        // 통신이 다시 오면 확인 필요가 풀린다
        api.jdbc.sql("update agent_session set last_report_at = now() where id = :id").param("id", fresh).update();
        assertThat(byMember(get(base + "/agents", member, 200).get("items")).get(memberId)
                .get("status_check_required").asBoolean()).isFalse();
    }

    /**
     * 깨지면: 페이지를 넘기는 사이 재연결한 멤버가 목록에서 사라지거나, 폐기 토큰·나간 멤버의 세션이 보이거나,
     * 정상 종료한 에이전트가 목록에서 빠지거나, 바깥 사람에게 프로젝트가 드러난다.
     */
    @Test
    void list_pagesByMember_hidesRevokedTokensAndEndedMembers_keepsClosedSessions() throws Exception {
        String base = "/api/projects/" + projectId;
        long memberId = api.memberId("member@test");
        long leaderId = api.memberId("leader@test");
        long first = Math.max(memberId, leaderId);
        long second = Math.min(memberId, leaderId);
        Map<Long, Long> tokenOf = Map.of(memberId, api.agentTokenId(), leaderId, api.leaderAgentTokenId());
        long firstSession = session(tokenOf.get(first), "first-s", "1 minutes");
        long secondSession = session(tokenOf.get(second), "second-s", "1 minutes");

        JsonNode page1 = get(base + "/agents?limit=1", member, 200);
        assertThat(page1.get("items")).hasSize(1);
        assertThat(page1.get("items").get(0).get("member_id").asLong()).isEqualTo(first);   // 멤버 번호 내림차순
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        // 페이지 사이에 둘째 멤버가 다시 연결해도(세션 번호가 커져도) 다음 페이지에 나온다
        long secondFresh = reconnect(tokenOf.get(second), secondSession, "second-fresh", "0 minutes");
        JsonNode page2 = get(base + "/agents?limit=1&cursor=" + page1.get("next_cursor").asText(), member, 200);
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.get("items").get(0).get("member_id").asLong()).isEqualTo(second);
        assertThat(page2.get("items").get(0).get("id").asLong()).isEqualTo(secondFresh);
        assertThat(page2.get("has_more").asBoolean()).isFalse();

        // DELETE 로 정상 종료한 세션도 마지막 연결로 남는다 — 끊김이고 확인 필요 아님
        end(firstSession, "CLOSED");
        JsonNode closed = byMember(get(base + "/agents", member, 200).get("items")).get(String.valueOf(first));
        assertThat(closed.get("id").asLong()).isEqualTo(firstSession);
        assertThat(closed.get("connected").asBoolean()).isFalse();
        assertThat(closed.get("status_check_required").asBoolean()).isFalse();

        // 토큰을 폐기하면 그 토큰의 세션은 빠진다 (MON-A05·팀원 목록과 같음)
        api.jdbc.sql("update agent_token set revoked_at = now() where id = :id").param("id", tokenOf.get(first)).update();
        JsonNode afterRevoke = get(base + "/agents", member, 200).get("items");
        assertThat(afterRevoke).hasSize(1);
        assertThat(afterRevoke.get(0).get("member_id").asLong()).isEqualTo(second);

        // 나간 멤버의 세션은 빠진다 (토큰이 남아 있어도)
        api.jdbc.sql("update project_member set active = false where id = :id").param("id", second).update();
        Long viewer = second == memberId ? leader : member;
        assertThat(get(base + "/agents", viewer, 200).get("items")).isEmpty();

        get(base + "/agents", outsider, 404);
        get(base + "/agents?cursor=abc", viewer, 400);
    }
}
