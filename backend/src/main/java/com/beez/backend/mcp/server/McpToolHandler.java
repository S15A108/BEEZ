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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidDefinitionException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * 모든 도구 호출의 공통 처리 (-176). {@link McpTool} 을 SDK 도구 등록 객체로 감싼다.
 * <ol>
 *   <li>호출자: 필터가 검증한 토큰 주체({@link AgentPrincipal})에서 {@code CallerContext.agent} 와 프로젝트를 만든다</li>
 *   <li>인수: snake_case JSON → 인수 레코드. 변환 실패(타입·enum 이름·모르는 키)는 400 validation_failed</li>
 *   <li>결과: 성공은 payload 를 snake_case JSON 으로, 업무 오류는 {@code {"error":{"code","message","details"?}}} 를 도구 실패 결과(isError)로</li>
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
        // 성공·오류 결과를 만든 뒤에만 바꾼다. 그 사이에 Error 등이 새면 로그가 ok 로 남지 않게 한다
        String outcome = "internal";
        try {
            if (principal == null) {
                // /mcp 필터 체인이 인증을 요구하므로 정상 경로에서는 오지 않는다
                throw AccessErrors.agentUnauthenticated();
            }
            McpCall call = new McpCall(CallerContext.agent(principal.accountId(), principal.agentTokenId()),
                    principal.projectId(), exchange.sessionId());
            A args = convertArgs(tool, arguments);
            CallToolResult result = success(tool.call(call, args));
            outcome = "ok";
            return result;
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

    /**
     * 모르는 인수 키는 거부한다(-179 결정). REST 는 Boot 기본대로 무시하지만, 도구에서 선택 인수 오타(task_id → taskid)를
     * 무시하면 upsert_task_plan 이 수정 대신 새 작업을 만드는 식으로 뜻이 바뀐 채 성공한다. 그래서 인수 변환에만 켠다.
     * 중첩 객체(plan·items 등)도 같다. 자유 객체 칸(Map)은 그대로 받는다.
     */
    private <A> A convertArgs(McpTool<A> tool, Map<String, Object> arguments) {
        try {
            return json.readerFor(tool.argsType())
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue((JsonNode) json.valueToTree(arguments == null ? Map.of() : arguments));
        } catch (InvalidDefinitionException e) {
            // 인수 레코드를 만들 수 없는 정의(서버 버그)다. 입력 탓으로 돌리지 않고 internal + 스택 로그로 보낸다
            throw new IllegalStateException("MCP 도구 " + tool.name() + " 의 인수 타입을 만들 수 없습니다", e);
        } catch (JacksonException e) {
            // Jackson 3 은 변환 오류를 감싸지 않고 그대로 던진다 (모르는 키는 UnrecognizedPropertyException)
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", argumentMessage(e));
        }
    }

    /** 칸 경로만 알려 준다. 모르는 키면 그렇다고 적는다 — 에이전트가 오타를 고칠 수 있게 */
    private static String argumentMessage(JacksonException e) {
        String path = argumentPath(e);
        if (e instanceof UnrecognizedPropertyException) {
            return path + " 은(는) 이 도구에 없는 인수입니다";
        }
        return path.isEmpty() ? "도구 인수가 올바르지 않습니다" : path + " 값이 올바르지 않습니다";
    }

    /** Jackson 원문 메시지는 클래스 이름 등 내부 정보를 담아 내보내지 않고, 칸 경로만 만든다 */
    private static String argumentPath(JacksonException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) path.append('.');
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private CallToolResult success(Object payload) {
        // 한 번만 snake_case 값(Map·List·스칼라)으로 바꾸고, 같은 값을 text 와 structuredContent 에 쓴다
        Object value = json.convertValue(payload == null ? Map.of() : payload, Object.class);
        CallToolResult.Builder result = CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(json.writeValueAsString(value)).build()))
                .isError(false);
        if (value instanceof Map<?, ?>) {
            result.structuredContent(value);
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

    /**
     * ApiException 은 REST {@code handleApi} 와 같은 본문(code·message·details)으로, 나머지는 REST 의
     * {@link GlobalExceptionHandler} 대응표를 그대로 탄다 (같은 오류 = 같은 본문, -267). details 가 없으면 non_null 로 빠진다
     */
    private ApiErrorResponse toError(Exception e) {
        if (e instanceof ApiException api) {
            return ApiErrorResponse.of(api.getCode(), api.getMessage(), api.getDetails());
        }
        Method method = restErrorMethods.resolveMethodByThrowable(e);
        if (method != null) {
            try {
                Object response = method.invoke(restErrors, e);
                if (response instanceof ResponseEntity<?> entity && entity.getBody() instanceof ApiErrorResponse body) {
                    return body;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 핸들러 시그니처가 (예외 하나) 가 아니게 바뀌었거나 핸들러가 실패했다. 아래 internal 로 떨어진다
            }
        }
        log.error("MCP 도구 처리 중 처리되지 않은 예외", e);
        return ApiErrorResponse.of("internal", "서버 내부 오류");
    }
}
