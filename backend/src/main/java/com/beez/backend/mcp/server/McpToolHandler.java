package com.beez.backend.mcp.server;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.api.ApiErrorResponse;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.GlobalExceptionHandler;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.web.RequestIdFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 모든 도구 호출의 공통 처리 (-176). {@link McpTool} 을 SDK 도구 등록 객체로 감싼다.
 * <ol>
 *   <li>호출자: 필터가 검증한 토큰 주체({@link AgentPrincipal})에서 {@code CallerContext.agent} 와 프로젝트를 만든다</li>
 *   <li>인수: snake_case JSON → 인수 레코드. 변환 실패(타입·enum 이름)는 400 validation_failed</li>
 *   <li>결과: 성공은 payload 를 snake_case JSON 으로, 업무 오류는 {@code {"error":{"code","message"}}} 를 도구 실패 결과(isError)로</li>
 *   <li>로그: 요청 ID(MDC)와 도구·프로젝트·계정·결과 코드·지연. 인수·결과 본문은 남기지 않는다 (비밀값·개인 대화 원문 — MCP-09)</li>
 * </ol>
 * 감사 기록(mcp_audit_log)은 -177 이 이 자리에 붙인다.
 */
@Component
public class McpToolHandler {

    /** {@link McpServerConfig} 의 transport context 키 */
    static final String PRINCIPAL_KEY = "beez.agentPrincipal";
    static final String REQUEST_ID_KEY = "beez.requestId";

    private static final Logger log = LoggerFactory.getLogger("com.beez.mcp");

    private final ObjectMapper json;
    private final GlobalExceptionHandler restErrors;
    private final ExceptionHandlerMethodResolver restErrorMethods =
            new ExceptionHandlerMethodResolver(GlobalExceptionHandler.class);

    /**
     * @param json       Spring 이 만든 ObjectMapper — REST 와 같은 snake_case·non_null 설정을 쓴다
     * @param restErrors ApiException 밖의 예외(낙관적 잠금·DB 제약 등)를 REST 와 같은 code 로 바꾸려고 재사용한다
     */
    public McpToolHandler(ObjectMapper json, GlobalExceptionHandler restErrors) {
        this.json = json;
        this.restErrors = restErrors;
    }

    <A> SyncToolSpecification specification(McpTool<A> tool) {
        McpSchema.Tool definition = McpSchema.Tool.builder()
                .name(tool.name())
                .description(tool.description())
                .inputSchema(parseSchema(tool))
                .build();
        return SyncToolSpecification.builder()
                .tool(definition)
                .callHandler((exchange, request) -> handle(tool, exchange, request.arguments()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSchema(McpTool<?> tool) {
        try {
            return json.readValue(tool.inputSchema(), Map.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("MCP 도구 " + tool.name() + " 의 inputSchema 가 JSON 이 아닙니다", e);
        }
    }

    <A> CallToolResult handle(McpTool<A> tool, McpSyncServerExchange exchange, Map<String, Object> arguments) {
        McpTransportContext context = exchange.transportContext();
        // immediateExecution 이라 보통 요청 스레드에서 돌지만, 스레드가 바뀌어도 로그의 요청 ID 가 끊기지 않게 다시 건다
        boolean ownMdc = MDC.get(RequestIdFilter.MDC_KEY) == null && context.get(REQUEST_ID_KEY) != null;
        if (ownMdc) {
            MDC.put(RequestIdFilter.MDC_KEY, (String) context.get(REQUEST_ID_KEY));
        }
        long started = System.nanoTime();
        AgentPrincipal principal = context.get(PRINCIPAL_KEY) instanceof AgentPrincipal p ? p : null;
        String outcome = "ok";
        try {
            if (principal == null) {
                // /mcp 필터 체인이 인증을 요구하므로 정상 경로에서는 오지 않는다
                throw AccessErrors.agentUnauthenticated();
            }
            McpCall call = new McpCall(CallerContext.agent(principal.accountId(), principal.agentTokenId()),
                    principal.projectId(), exchange.sessionId());
            A args = convertArgs(tool, arguments);
            return success(tool.call(call, args));
        } catch (Exception e) {
            ApiErrorResponse body = toError(e);
            outcome = body.error().code();
            return failure(body);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            log.info("mcp tool={} project={} account={} token={} -> {} ({} ms)", tool.name(),
                    principal == null ? "-" : principal.projectId(),
                    principal == null ? "-" : principal.accountId(),
                    principal == null ? "-" : principal.agentTokenId(), outcome, ms);
            if (ownMdc) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            }
        }
    }

    private <A> A convertArgs(McpTool<A> tool, Map<String, Object> arguments) {
        try {
            return json.convertValue(arguments == null ? Map.of() : arguments, tool.argsType());
        } catch (JacksonException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", argumentMessage(e));
        } catch (IllegalArgumentException e) {
            // convertValue 가 변환 오류를 IllegalArgumentException 으로 감싸는 경우
            if (e.getCause() instanceof JacksonException cause) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", argumentMessage(cause));
            }
            throw e;
        }
    }

    /** 칸 경로만 알려 준다. Jackson 원문 메시지는 클래스 이름 등 내부 정보를 담아 내보내지 않는다 */
    private static String argumentMessage(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) path.append('.');
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? "도구 인수가 올바르지 않습니다" : path + " 값이 올바르지 않습니다";
    }

    private CallToolResult success(Object payload) {
        JsonNode tree = json.valueToTree(payload == null ? Map.of() : payload);
        CallToolResult.Builder result = CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(json.writeValueAsString(tree)).build()))
                .isError(false);
        if (tree.isObject()) {
            result.structuredContent(json.convertValue(tree, Map.class));
        }
        return result.build();
    }

    private CallToolResult failure(ApiErrorResponse body) {
        return CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(json.writeValueAsString(body)).build()))
                .structuredContent(json.convertValue(body, Map.class))
                .isError(true)
                .build();
    }

    /** ApiException 은 그대로, 나머지는 REST 의 {@link GlobalExceptionHandler} 대응표를 그대로 탄다 (같은 오류 = 같은 code) */
    private ApiErrorResponse toError(Exception e) {
        if (e instanceof ApiException api) {
            return ApiErrorResponse.of(api.getCode(), api.getMessage());
        }
        Method method = restErrorMethods.resolveMethodByThrowable(e);
        if (method != null) {
            try {
                Object response = method.invoke(restErrors, e);
                if (response instanceof ResponseEntity<?> entity && entity.getBody() instanceof ApiErrorResponse body) {
                    return body;
                }
            } catch (IllegalAccessException | InvocationTargetException ignored) {
                // 아래 internal 로 떨어진다
            }
        }
        log.error("MCP 도구 처리 중 처리되지 않은 예외", e);
        return ApiErrorResponse.of("internal", "서버 내부 오류");
    }
}
