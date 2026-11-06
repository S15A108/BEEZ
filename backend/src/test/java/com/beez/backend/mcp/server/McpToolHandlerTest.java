package com.beez.backend.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.GlobalExceptionHandler;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도구 오류 → 도구 실패 결과 변환 (-267), 감사 기록의 결과 분류·저장 실패 처리 (-177).
 * 실제 직렬화 설정(non_null)과 감사 행 저장까지 본 경로는 McpToolsIntegrationTests 가 본다.
 */
class McpToolHandlerTest {

    private final ObjectMapper json = JsonMapper.builder().build();
    private final McpAuditRecorder audits = mock(McpAuditRecorder.class);
    private final McpToolHandler handler = new McpToolHandler(json, new GlobalExceptionHandler(), audits);

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

    /**
     * 깨지면: 다른 프로젝트·없는 ID 접근 거부가 일반 실패(ERROR)와 섞여 감사에서 골라낼 수 없거나, 거부 사유에 프로젝트·계정 번호가 섞여 남는다
     * (Jira -177 작업 3, 05 문서 참고 2 deny_reason).
     */
    @Test
    void accessDenial_isAuditedAsDenied_withReasonKindOnly() {
        handler.handle(throwing(AccessErrors.notFound("task_not_in_project task=5 project=2")), exchange(),
                Map.of("task_id", "5"));
        handler.handle(throwing(new ApiException(HttpStatus.CONFLICT, "revision_conflict", "충돌")), exchange(), Map.of());

        ArgumentCaptor<McpAuditEntry> entries = ArgumentCaptor.forClass(McpAuditEntry.class);
        verify(audits, org.mockito.Mockito.times(2)).record(entries.capture());
        McpAuditEntry denied = entries.getAllValues().get(0);
        assertThat(denied.result()).isEqualTo("DENIED");
        assertThat(denied.denyReason()).isEqualTo("task_not_in_project");
        assertThat(denied.errorCode()).isEqualTo("not_found");
        assertThat(denied.targetIds()).containsExactly(Map.of("field", "task_id", "id", "5"));
        assertThat(List.of(denied.agentTokenId(), denied.accountId(), denied.projectId())).containsExactly(3L, 1L, 2L);
        McpAuditEntry failed = entries.getAllValues().get(1);
        assertThat(failed.result()).isEqualTo("ERROR");
        assertThat(failed.denyReason()).isNull();
        assertThat(failed.errorCode()).isEqualTo("revision_conflict");
    }

    /** 깨지면: 감사 저장 실패 때문에 이미 커밋된 변경이 실패로 보여 에이전트가 같은 변경을 다시 부른다 (전진 결정, -177). */
    @Test
    void auditWriteFailure_keepsToolResult() {
        doThrow(new IllegalStateException("db down")).when(audits).record(any());

        CallToolResult result = handler.handle(returning(Map.of("id", "7")), exchange(), Map.of());

        assertThat(result.isError()).isFalse();
        assertThat(result.structuredContent()).isEqualTo(Map.of("id", "7"));
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
        return tool(() -> { throw e; });
    }

    @SuppressWarnings("rawtypes")
    private static McpTool<Map> returning(Object payload) {
        return tool(() -> payload);
    }

    @SuppressWarnings("rawtypes")
    private static McpTool<Map> tool(java.util.function.Supplier<Object> body) {
        return new McpTool<>() {
            @Override public String name() { return "test_tool"; }
            @Override public String description() { return "시험용"; }
            @Override public String inputSchema() { return "{\"type\":\"object\"}"; }
            @Override public Class<Map> argsType() { return Map.class; }
            @Override public Object call(McpCall call, Map args) { return body.get(); }
        };
    }
}
