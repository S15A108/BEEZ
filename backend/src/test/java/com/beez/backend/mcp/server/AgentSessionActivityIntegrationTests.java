package com.beez.backend.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 활동의 에이전트 연결 세션 (S15P31A108-333). 실제 MCP 클라이언트가 도구로 활동을 만들고, 타임라인(MON-A04)을
 * {@code agent_session_id} 로 거른다. 서비스별 활동 규칙은 각 서비스 시험에 있고, 여기서는 "어느 연결이 했는가"만 본다.
 */
class AgentSessionActivityIntegrationTests extends IntegrationTestSupport {

    private static final String ORIGIN = "http://localhost:5173";

    @Autowired
    DataSource dataSource;

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    TaskApiSupport api;
    long projectId;
    String memberToken;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        memberToken = SecretTokens.generate();
        api.jdbc.sql("update agent_token set token_hash = :h where id = :id")
                .param("h", SecretTokens.hash(memberToken)).param("id", api.agentTokenId()).update();
    }

    /**
     * 깨지면: 같은 토큰이 다시 연결한 뒤의 활동이 옛 연결로 남거나(토큰 → 세션을 나중에 다시 고르는 방식), 에이전트 활동의 세션이 비어
     * 타임라인 세션 필터가 늘 빈 결과다. 사람 활동에 세션이 붙어도 깨진다 (Jira -333 완료 조건 1~3).
     */
    @Test
    void agentActivity_carriesTheSessionOfItsRequest_andTimelineFiltersBySession() {
        String first;
        String second;
        McpSyncClient a = connect(memberToken);
        McpSyncClient b = connect(memberToken);
        try {
            a.initialize();
            first = ok(a, "check_connection", Map.of()).get("agent_session_id").asString();
            ok(a, "propose_design_change", proposal("세션 A 제안", "k-a"));

            // 같은 토큰으로 다시 연결한다 — 앞 연결은 밀려나고, 이후 호출은 새 연결의 기록으로 남아야 한다
            b.initialize();
            second = ok(b, "check_connection", Map.of()).get("agent_session_id").asString();
            assertThat(second).isNotEqualTo(first);
            ok(b, "propose_design_change", proposal("세션 B 제안", "k-b"));
        } finally {
            a.closeGracefully();
            b.closeGracefully();
        }

        // 사람이 만든 활동은 세션이 비어 있다
        HttpResponse<String> human = api.postWithHeaders("/api/projects/" + projectId + "/change-proposals",
                json.writeValueAsString(proposalBody("사람 제안")), api.accountId("leader@test"),
                "Origin", ORIGIN, "Idempotency-Key", "k-human");
        assertThat(human.statusCode()).as(human.body()).isIn(200, 201);

        List<JsonNode> all = timeline("");
        assertThat(all).extracting(i -> i.get("type").asString() + "/" + i.get("actor_type").asString() + "/"
                        + (i.hasNonNull("agent_session_id") ? i.get("agent_session_id").asString() : "-"))
                .containsExactlyInAnyOrder(
                        "CHANGE_PROPOSAL_SUBMITTED/AGENT/" + first,
                        "CHANGE_PROPOSAL_SUBMITTED/AGENT/" + second,
                        "CHANGE_PROPOSAL_SUBMITTED/HUMAN/-");

        assertThat(timeline("&agent_session_id=" + first)).extracting(i -> i.get("agent_session_id").asString())
                .containsExactly(first);
        assertThat(timeline("&agent_session_id=" + second)).extracting(i -> i.get("agent_session_id").asString())
                .containsExactly(second);
    }

    // ---- 도우미 ----

    private Map<String, Object> proposal(String title, String idempotencyKey) {
        Map<String, Object> args = new java.util.HashMap<>(proposalBody(title));
        args.put("idempotency_key", idempotencyKey);
        return args;
    }

    /** API-01 설명만 바꾸는 제안. MCP 인수와 REST 본문이 같은 모양이다 */
    private Map<String, Object> proposalBody(String title) {
        return Map.of(
                "base_baseline_id", String.valueOf(api.jdbc.sql("select current_baseline_id from project where id = :p")
                        .param("p", projectId).query(Long.class).single()),
                "title", title, "reason", "응답 설명을 명확히 한다",
                "items", List.of(Map.of("operation", "UPDATE",
                        "design_item_id", String.valueOf(api.designItemId("API-01")),
                        "base_design_item_version_id", String.valueOf(api.currentVersionId("API-01")),
                        "schema_version", 1,
                        "proposed_content", Map.of("description", title, "method", "GET", "path", "/users"))),
                "documents", List.of());
    }

    /** 팀원이 보는 타임라인의 변경 제안 제출 활동 (픽스처의 다른 활동은 거른다) */
    private List<JsonNode> timeline(String query) {
        HttpResponse<String> res = api.get("/api/projects/" + projectId + "/activities?type=CHANGE_PROPOSAL_SUBMITTED"
                + query, api.accountId("member@test"));
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        json.readTree(res.body()).get("items").forEach(items::add);
        return items;
    }

    /** 닫을 때는 closeGracefully() — close() 는 종료 DELETE 를 기다리지 않아 다음 시험의 truncate 와 교착한다 (CI 40P01) */
    private McpSyncClient connect(String token) {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + env.getRequiredProperty("local.server.port"))
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
    }

    private JsonNode ok(McpSyncClient client, String tool, Map<String, Object> args) {
        CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder().name(tool).arguments(args).build());
        assertThat(result.isError()).as("도구 실패: %s", ((McpSchema.TextContent) result.content().get(0)).text()).isFalse();
        return json.valueToTree(result.structuredContent());
    }
}
