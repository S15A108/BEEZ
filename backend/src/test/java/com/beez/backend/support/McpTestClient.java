package com.beez.backend.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 실제 MCP SDK 클라이언트로 /mcp 도구를 부르는 시험 도우미. Bearer 토큰으로 붙는다.
 * MCP 통합 시험(McpServer·McpTools·AgentConnection·AC-10 인수 시험)이 모두 이 연결을 쓴다 — 연결 절차가 바뀌면 여기만 고친다.
 *
 * <p>닫을 때는 close()·try-with-resources 대신 closeGracefully() 를 쓴다. close() 는 세션 종료 DELETE 를 기다리지 않아
 * 그 요청의 토큰 확인 트랜잭션이 다음 시험(같은 컨텍스트의 다른 클래스 포함)의 truncate … cascade 와 교착한다(CI 40P01).
 */
public final class McpTestClient {

    private McpTestClient() {
    }

    /** 연결만 만든다. 부르는 쪽이 initialize() 하고, 끝나면 closeGracefully() 로 닫는다. clientInfo 는 SDK 기본값 */
    public static McpSyncClient connect(int port, String token) {
        return McpClient.sync(transport(port, token)).requestTimeout(Duration.ofSeconds(10)).build();
    }

    /** clientInfo 이름을 정해 연결한다 — 연결 기록(agent_session)의 클라이언트 이름을 보는 시험용 (-178) */
    public static McpSyncClient connect(int port, String token, String clientName) {
        return McpClient.sync(transport(port, token))
                .clientInfo(new McpSchema.Implementation(clientName, "9.9.9"))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static HttpClientStreamableHttpTransport transport(int port, String token) {
        return HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
    }

    public static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> args) {
        return client.callTool(McpSchema.CallToolRequest.builder().name(tool).arguments(args).build());
    }

    /** 성공 결과의 payload. 실패면 오류 본문을 보이며 깨진다 */
    public static JsonNode ok(ObjectMapper json, CallToolResult result) {
        assertThat(result.isError()).as("도구 실패: %s", ((McpSchema.TextContent) result.content().get(0)).text()).isFalse();
        return json.valueToTree(result.structuredContent());
    }

    /** 실패 결과의 error 객체 (code·message·details) */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> error(CallToolResult result) {
        assertThat(result.isError()).isTrue();
        return (Map<String, Object>) ((Map<String, Object>) result.structuredContent()).get("error");
    }
}
