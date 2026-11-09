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
 * ({@link AgentConnectionRule}: 끝나지 않았고 2시간 안에 통신, 30분 넘게 조용하면 확인 필요)으로 연결을 보는지 본다.
 * Claude Code 는 /exit 때 DELETE 를 보내지 않아(11-09 실측) 끝낸 세션도 ended_at 이 비어 남는다 — 시간 창이 그 세션을 걸러야 한다.
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

    @Test
    void quietSessionWithoutDelete_isNotConnected_onAllThreeViews_andReconnectRestoresIt() throws Exception {
        String base = "/api/projects/" + projectId;
        String memberId = String.valueOf(api.memberId("member@test"));
        String leaderId = String.valueOf(api.memberId("leader@test"));
        long quiet = session(api.agentTokenId(), "member-quiet", "3 hours");          // /exit 뒤 DELETE 없이 3시간
        long slow = session(api.leaderAgentTokenId(), "leader-slow", "40 minutes");   // 연결은 살아 있으나 30분 넘게 조용

        // AGT-A05: 멤버마다 최근 세션 한 줄. 조용한 세션은 끊김, 늦은 세션은 연결됨 + 확인 필요
        Map<String, JsonNode> a05 = byMember(get(base + "/agents", member, 200).get("items"));
        assertThat(a05).containsOnlyKeys(memberId, leaderId);
        assertThat(a05.get(memberId).get("id").asLong()).isEqualTo(quiet);
        assertThat(a05.get(memberId).get("connected").asBoolean()).isFalse();
        assertThat(a05.get(memberId).get("status_check_required").asBoolean()).isFalse();
        assertThat(a05.get(memberId).has("last_report_at")).isTrue();                   // 끊겨도 마지막 통신 시각은 보인다
        assertThat(a05.get(leaderId).get("id").asLong()).isEqualTo(slow);
        assertThat(a05.get(leaderId).get("connected").asBoolean()).isTrue();
        assertThat(a05.get(leaderId).get("status_check_required").asBoolean()).isTrue();
        assertThat(a05.get(leaderId).has("last_error_code")).isFalse();

        // MON-A05: 살아 있는 세션만 (예전에는 ended_at 만 봐서 3시간 조용한 세션도 나왔다)
        JsonNode work = get(base + "/agents/work-status", member, 200).get("items");
        assertThat(work).hasSize(1);
        assertThat(work.get(0).get("agent_session_id").asLong()).isEqualTo(slow);

        // 팀원 목록: 같은 판정
        assertThat(memberListConnected()).containsEntry(memberId, false).containsEntry(leaderId, true);

        // 다시 연결하면 세 곳 모두 연결됨
        long fresh = session(api.agentTokenId(), "member-fresh", "0 minutes");
        JsonNode again = byMember(get(base + "/agents", member, 200).get("items")).get(memberId);
        assertThat(again.get("id").asLong()).isEqualTo(fresh);
        assertThat(again.get("connected").asBoolean()).isTrue();
        assertThat(again.get("status_check_required").asBoolean()).isFalse();
        assertThat(get(base + "/agents/work-status?member_id=" + memberId, member, 200).get("items").get(0)
                .get("agent_session_id").asLong()).isEqualTo(fresh);
        assertThat(memberListConnected()).containsEntry(memberId, true);
    }

    @Test
    void list_pages_hidesRevokedTokens_andHidesProjectFromOutsiders() throws Exception {
        String base = "/api/projects/" + projectId;
        long memberSession = session(api.agentTokenId(), "member-s", "1 minutes");
        long leaderSession = session(api.leaderAgentTokenId(), "leader-s", "1 minutes");

        JsonNode page1 = get(base + "/agents?limit=1", member, 200);
        assertThat(page1.get("items")).hasSize(1);
        assertThat(page1.get("items").get(0).get("id").asLong()).isEqualTo(leaderSession);   // 세션 번호 내림차순
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        JsonNode page2 = get(base + "/agents?limit=1&cursor=" + page1.get("next_cursor").asText(), member, 200);
        assertThat(page2.get("items").get(0).get("id").asLong()).isEqualTo(memberSession);
        assertThat(page2.get("has_more").asBoolean()).isFalse();

        // 토큰을 폐기하면 그 토큰의 세션은 연결 현황에서 빠진다 (MON-A05·팀원 목록과 같음)
        api.jdbc.sql("update agent_token set revoked_at = now() where id = :id").param("id", api.leaderAgentTokenId()).update();
        JsonNode afterRevoke = get(base + "/agents", member, 200).get("items");
        assertThat(afterRevoke).hasSize(1);
        assertThat(afterRevoke.get(0).get("id").asLong()).isEqualTo(memberSession);

        get(base + "/agents", outsider, 404);
        get(base + "/agents?cursor=abc", member, 400);
    }
}
