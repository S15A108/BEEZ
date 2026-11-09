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
 * McpToolsIntegrationTests 등 기존 MCP 시험에도 같은 도우미가 있다 — 그 시험을 고치는 -178 이 머지된 뒤 이 클래스로 모은다.
 */
public final class McpTestClient {

    private McpTestClient() {
    }

    /** 연결만 만든다. 부르는 쪽이 initialize() 하고, 끝나면 closeGracefully() 로 닫는다 (종료 DELETE 가 다음 시험의 truncate 와 교착하지 않게) */
    public static McpSyncClient connect(int port, String token) {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .httpRequestCustomizer((builder, method, uri, body, context) ->
                        builder.header("Authorization", "Bearer " + token))
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build();
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
