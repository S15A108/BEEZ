package com.beez.backend.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.McpTestClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 연결·식별·해제·중복 연결 (-178). 실제 MCP SDK 클라이언트가 /mcp 에 붙는다 — Jira -70 완료 조건
 * "실제 클라이언트 연결·호출·해제 확인, 권한 실패·연결 실패·중복 에이전트 처리".
 */
class AgentConnectionIntegrationTests extends IntegrationTestSupport {

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate db;

    record Issued(String token, long tokenId) {
    }

    /**
     * 깨지면: 연결이 누구(어떤 클라이언트·토큰·계정)인지 남지 않거나, 연결 시험 도구가 없거나, 닫은 연결이 살아 있는 것으로 남는다
     * (Jira -178 작업 1·3·5, 02-mcp-tools MCP-15).
     */
    @Test
    void connect_call_close_isRecordedPerSession() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        Issued issued = issue(leader, p.projectId(), "개발 PC");

        McpSyncClient client = connect(issued.token(), "claude-code");
        String sessionId;
        try {
            McpSchema.InitializeResult init = client.initialize();
            // 작업 절차 안내(AGT-04)가 연결할 때 함께 간다
            assertThat(init.instructions()).contains("validate_task_plan").contains("check_connection").contains("get_task");

            JsonNode check = ok(client, "check_connection");
            sessionId = check.get("session_id").asString();
            assertThat(check.get("connected").asBoolean()).isTrue();
            assertThat(check.get("project_id").asString()).isEqualTo(String.valueOf(p.projectId()));
            assertThat(check.get("account_id").asString()).isEqualTo(String.valueOf(p.leaderAccountId()));
            assertThat(check.get("member_id").asString()).isEqualTo(String.valueOf(p.leaderMemberId()));
            assertThat(check.get("agent_token_id").asString()).isEqualTo(String.valueOf(issued.tokenId()));
            assertThat(check.get("token_label").asString()).isEqualTo("개발 PC");
            assertThat(check.get("client_name").asString()).isEqualTo("claude-code");
            assertThat(check.get("client_version").asString()).isEqualTo("9.9.9");
            // 계정·토큰·MCP 세션 ID 는 서로 다른 식별자다 — 연결 기록 ID 로 셋을 잇는다
            Map<String, Object> row = session(sessionId);
            assertThat(String.valueOf(row.get("id"))).isEqualTo(check.get("agent_session_id").asString());
            assertThat(row.get("agent_token_id")).isEqualTo(issued.tokenId());
            assertThat(row.get("last_report_at")).isNotNull();
            assertThat(row.get("ended_at")).isNull();
            // 팀원 목록(MEM-A06)의 연결 표시
            assertThat(agentConnected(leader, p.projectId(), p.leaderAccountId())).isTrue();

            // 2시간 넘게 통신이 없으면(DELETE 없이 꺼진 클라이언트·재시작) 연결로 보지 않는다. 다시 통신하면 돌아온다
            db.update("update agent_session set last_report_at = now() - interval '3 hours' where session_key = ?",
                    sessionId);
            assertThat(agentConnected(leader, p.projectId(), p.leaderAccountId())).isFalse();
            ok(client, "check_connection");
            assertThat(agentConnected(leader, p.projectId(), p.leaderAccountId())).isTrue();
        } finally {
            client.closeGracefully();
        }
        assertThat(agentConnected(leader, p.projectId(), p.leaderAccountId())).isFalse();

        // 해제: 클라이언트가 DELETE /mcp 로 닫으면 연결이 끝난다
        Map<String, Object> closed = session(sessionId);
        assertThat(closed.get("ended_at")).isNotNull();
        assertThat(closed.get("end_reason")).isEqualTo("CLOSED");
    }

    /**
     * 깨지면: 같은 사람이 한 프로젝트에 에이전트를 여러 개 붙여 서로 다른 작업 상태를 보고하거나(1인 1에이전트, Q06),
     * 밀려난 클라이언트가 404 를 받아 조용히 다시 붙으며 새 연결을 끝없이 밀어낸다.
     * 다른 팀원의 연결은 건드리지 않는다.
     */
    @Test
    void sameMemberReconnecting_replacesOldSession_with409_butNotTeammates() {
        var p = fixtures().projectWithLeader();
        String mateEmail = AccessFixtures.uniqueEmail();
        long mate = fixtures().account(mateEmail);
        fixtures().member(p.projectId(), mate, ProjectRole.MEMBER);
        Issued desktop = issue(p.leaderEmail(), p.projectId(), "데스크톱");
        Issued laptop = issue(p.leaderEmail(), p.projectId(), "노트북");
        Issued mateToken = issue(mateEmail, p.projectId(), null);

        McpSyncClient first = connect(desktop.token(), "claude-code");
        McpSyncClient teammate = connect(mateToken.token(), "claude-code");
        McpSyncClient second = connect(laptop.token(), "claude-code");
        try {
            first.initialize();
            teammate.initialize();
            String firstSession = ok(first, "check_connection").get("session_id").asString();

            // 같은 사람이 다른 토큰(다른 PC)으로 새로 연결한다 — 나중 연결이 이긴다
            second.initialize();
            assertThat(ok(second, "check_connection").get("token_label").asString()).isEqualTo("노트북");

            assertThatThrownBy(() -> first.callTool(tool("check_connection")))
                    .hasMessageContaining("agent_session_replaced");
            assertThat(session(firstSession).get("end_reason")).isEqualTo("REPLACED");
            assertThat(ok(teammate, "check_connection").get("account_id").asString()).isEqualTo(String.valueOf(mate));

            // 밀려난 세션은 에이전트 작업 현황(MON-A05)에 남지 않는다 — 팀장 세션은 새 연결 하나만
            ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
            String secondSession = ok(second, "check_connection").get("agent_session_id").asString();
            JsonNode work = json.readTree(leader.get("/api/projects/" + p.projectId() + "/agents/work-status?member_id="
                    + p.leaderMemberId()).body());
            assertThat(work.get("items")).extracting(i -> i.get("agent_session_id").asString())
                    .containsExactly(secondSession);
            assertThat(agentConnected(leader, p.projectId(), p.leaderAccountId())).isTrue();
        } finally {
            // 밀려난 세션의 DELETE 는 거부하지 않는다 — SDK 가 서버 메모리의 세션을 지울 수 있게
            first.closeGracefully();
            teammate.closeGracefully();
            second.closeGracefully();
        }
        assertThat(db.queryForObject("""
                select count(*) from agent_session s join agent_token t on t.id = s.agent_token_id
                 where t.project_id = ? and s.ended_at is null""", Integer.class, p.projectId())).isZero();
    }

    /**
     * 깨지면: 다른 토큰(다른 프로젝트·다른 사람)이 남의 MCP 세션 ID 로 그 세션을 끊거나 이어 쓸 수 있다
     * (-176 PR #103 hapung 리뷰 메모 "세션과 토큰 결속"). 남의 세션은 없는 세션처럼 404 다.
     */
    @Test
    void otherToken_cannotUseOrCloseSomeoneElsesSession() {
        var a = fixtures().projectWithLeader();
        var b = fixtures().projectWithLeader();
        Issued owner = issue(a.leaderEmail(), a.projectId(), null);
        Issued intruder = issue(b.leaderEmail(), b.projectId(), null);

        McpSyncClient client = connect(owner.token(), "claude-code");
        try {
            client.initialize();
            String sessionId = ok(client, "check_connection").get("session_id").asString();
            ApiClient raw = client();

            String call = """
                    {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"check_connection","arguments":{}}}""";
            HttpResponse<String> hijack = raw.post("/mcp", call, "Accept", "application/json, text/event-stream",
                    "Authorization", "Bearer " + intruder.token(), "Mcp-Session-Id", sessionId);
            assertThat(hijack.statusCode()).isEqualTo(404);
            // 남의 세션과 없는 세션은 같은 응답이다 — 응답으로 남의 세션이 있는지 알 수 없다
            HttpResponse<String> unknown = raw.post("/mcp", call, "Accept", "application/json, text/event-stream",
                    "Authorization", "Bearer " + owner.token(), "Mcp-Session-Id", "00000000-0000-0000-0000-000000000000");
            assertThat(unknown.statusCode()).isEqualTo(404);
            assertThat(json.readTree(hijack.body())).isEqualTo(json.readTree(unknown.body()));
            assertThat(json.readTree(hijack.body()).get("error").get("code").asString()).isEqualTo("not_found");

            HttpResponse<String> kill = raw.send(raw.request("/mcp", "Authorization", "Bearer " + intruder.token(),
                    "Mcp-Session-Id", sessionId).DELETE());
            assertThat(kill.statusCode()).isEqualTo(404);

            // 주인의 세션은 그대로 쓴다
            assertThat(ok(client, "check_connection").get("project_id").asString())
                    .isEqualTo(String.valueOf(a.projectId()));
            assertThat(session(sessionId).get("ended_at")).isNull();
        } finally {
            client.closeGracefully();
        }
    }

    /** 깨지면: 연결 해제(토큰 폐기) 뒤에도 열려 있던 세션으로 연결 시험·도구 호출이 된다 (AGT-A03 "폐기 커밋 이후 즉시 거부"). */
    @Test
    void revokedToken_isRejectedWith401_onOpenSession() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        Issued issued = issue(leader, p.projectId(), null);

        McpSyncClient client = connect(issued.token(), "claude-code");
        try {
            client.initialize();
            assertThat(ok(client, "check_connection").get("connected").asBoolean()).isTrue();

            leader.send(leader.request("/api/agent-tokens/" + issued.tokenId()).DELETE());

            assertThatThrownBy(() -> client.callTool(tool("check_connection")))
                    .isInstanceOf(McpHttpClientTransportAuthorizationException.class);
        } finally {
            client.closeGracefully();
        }
    }

    // ---- 도우미 ----

    /** 닫을 때는 closeGracefully() 를 쓴다 ({@link McpTestClient} 참고) */
    private McpSyncClient connect(String token, String clientName) {
        return McpTestClient.connect(Integer.parseInt(env.getRequiredProperty("local.server.port")), token, clientName);
    }

    private Issued issue(String email, long projectId, String label) {
        return issue(client().login(email, AccessFixtures.PASSWORD), projectId, label);
    }

    private Issued issue(ApiClient user, long projectId, String label) {
        String body = label == null ? "{}" : "{\"label\":\"" + label + "\"}";
        JsonNode issued = json.readTree(user.post("/api/projects/" + projectId + "/agent-tokens", body).body());
        return new Issued(issued.get("token").asString(), issued.get("token_info").get("id").asLong());
    }

    /** 팀원 목록(MEM-A06)에서 이 계정의 agent_connected */
    private boolean agentConnected(ApiClient viewer, long projectId, long accountId) {
        JsonNode page = json.readTree(viewer.get("/api/projects/" + projectId + "/members").body());
        for (JsonNode m : page.get("items")) {
            if (m.get("account_id").asString().equals(String.valueOf(accountId))) {
                return m.get("agent_connected").asBoolean();
            }
        }
        throw new AssertionError("member not found: " + accountId);
    }

    private Map<String, Object> session(String sessionKey) {
        return db.queryForMap("select * from agent_session where session_key = ?", sessionKey);
    }

    private static McpSchema.CallToolRequest tool(String name) {
        return McpSchema.CallToolRequest.builder().name(name).arguments(Map.of()).build();
    }

    private JsonNode ok(McpSyncClient client, String name) {
        CallToolResult result = client.callTool(tool(name));
        assertThat(result.isError()).as("%s", result.structuredContent()).isFalse();
        return json.valueToTree(result.structuredContent());
    }
}
