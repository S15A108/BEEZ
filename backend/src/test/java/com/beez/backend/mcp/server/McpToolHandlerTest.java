package com.beez.backend.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.GlobalExceptionHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 도구 오류 → 도구 실패 결과 변환 (-267). 실제 직렬화 설정(non_null)까지 본 경로는 McpToolsIntegrationTests 가 본다. */
class McpToolHandlerTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final McpToolHandler handler = new McpToolHandler(json, new GlobalExceptionHandler());

    /**
     * 깨지면: 명세가 error.details 를 정한 오류(예: ACC-A12 remaining_attempts)가 MCP 로는 details 없이 나가
     * REST 와 MCP 의 오류 본문이 달라진다 (-267 완료 조건 1).
     */
    @Test
    @SuppressWarnings("unchecked")
    void apiExceptionDetails_reachStructuredContentAndText() {
        McpTool<Map> tool = throwing(new ApiException(HttpStatus.BAD_REQUEST, "verification_code_mismatch",
                "인증 코드가 맞지 않습니다", Map.of("remaining_attempts", 2)));

        CallToolResult result = handler.handle(tool, exchange(), Map.of());

        assertThat(result.isError()).isTrue();
        Map<String, Object> error = (Map<String, Object>) ((Map<String, Object>) result.structuredContent()).get("error");
        assertThat(error).containsEntry("code", "verification_code_mismatch")
                .containsEntry("details", Map.of("remaining_attempts", 2));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(json.readTree(text).at("/error/details/remaining_attempts").asInt()).isEqualTo(2);
    }

    private static McpSyncServerExchange exchange() {
        McpSyncServerExchange exchange = mock(McpSyncServerExchange.class);
        when(exchange.transportContext()).thenReturn(McpTransportContext.create(
                Map.of(McpToolHandler.PRINCIPAL_KEY, new AgentPrincipal(1L, 2L, 3L))));
        when(exchange.sessionId()).thenReturn("session-1");
        return exchange;
    }

    @SuppressWarnings("rawtypes")
    private static McpTool<Map> throwing(ApiException e) {
        return new McpTool<>() {
            @Override public String name() { return "test_tool"; }
            @Override public String description() { return "시험용"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public Class<Map> argsType() { return Map.class; }
            @Override public Object call(McpCall call, Map args) { throw e; }
        };
    }
}
