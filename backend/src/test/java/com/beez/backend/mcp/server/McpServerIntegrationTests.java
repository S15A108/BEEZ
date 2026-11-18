package com.beez.backend.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 서버 기반 (-176). 실제 MCP SDK 클라이언트가 Streamable HTTP 로 /mcp 에 붙어 initialize → tools/list → tools/call 한다.
 * 실제 도메인 도구는 -179 몫이라, 공통 처리만 보는 시험용 도구 {@code probe} 를 이 컨텍스트에만 등록한다.
 */
class McpServerIntegrationTests extends IntegrationTestSupport {

    @Autowired
    Environment env;

    @Autowired
    ObjectMapper json;

    enum Mode { OK, API_ERROR, LOCK_CONFLICT }

    record ProbeArgs(String taskId, Mode mode, Integer count) {
    }

    record ProbeResult(long accountId, Long agentTokenId, long projectId, String taskId, Integer count,
                       boolean hasSession) {
    }

    @TestConfiguration
    static class ProbeTool {
        @Bean
        McpTool<ProbeArgs> probe() {
            return new McpTool<>() {
                public String name() { return "probe"; }
                public String description() { return "공통 처리 시험용"; }
                public String inputSchema() {
                    return """
                            {"type":"object","properties":{"task_id":{"type":"string"},
                             "mode":{"type":"string","enum":["OK","API_ERROR","LOCK_CONFLICT"]},
                             "count":{"type":"integer"}}}""";
                }
                public Class<ProbeArgs> argsType() { return ProbeArgs.class; }
                public Object call(McpCall call, ProbeArgs args) {
                    if (args.mode() == Mode.API_ERROR) {
                        throw new ApiException(HttpStatus.CONFLICT, "task_conflict", "다른 사람이 맡은 작업입니다");
                    }
                    if (args.mode() == Mode.LOCK_CONFLICT) {
                        throw new ObjectOptimisticLockingFailureException(Object.class, 1L);
                    }
                    return new ProbeResult(call.caller().accountId(), call.caller().agentTokenId(), call.projectId(),
                            args.taskId(), args.count(), call.sessionId() != null);
                }
            };
        }

        /** 인수 타입을 만들 수 없게 잘못 정의된 도구 (서버 버그 흉내) */
        @Bean
        McpTool<Runnable> brokenArgs() {
            return new McpTool<>() {
                public String name() { return "broken_args"; }
                public String description() { return "인수 타입 정의 오류 시험용"; }
                public String inputSchema() { return "{\"type\":\"object\"}"; }
                public Class<Runnable> argsType() { return Runnable.class; }
                public Object call(McpCall call, Runnable args) { return Map.of(); }
            };
        }
    }

    /** 깨지면: Claude Code 가 /mcp 에 붙어도 도구를 보거나 부를 수 없다 — 이 티켓 완료 조건 "도구 목록 조회·호출" (02-mcp-tools 연결과 인증). */
    @Test
    void sdkClient_listsAndCallsTool_asTokenCallerAndProject() {
        var p = fixtures().projectWithLeader();
        String token = issueToken(p.leaderEmail(), p.projectId());

        McpSyncClient client = connect(token);
        try {
            client.initialize();
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("probe");
            assertThat(client.listTools().tools().stream().filter(t -> t.name().equals("probe")).findFirst()
                    .orElseThrow().inputSchema()).containsKey("properties");

            CallToolResult result = call(client, Map.of("task_id", "501", "mode", "OK", "count", 3));

            assertThat(result.isError()).isFalse();
            // 인수는 snake_case 로 들어가고, 결과도 snake_case 로 나온다. 호출자·프로젝트는 인수가 아니라 토큰에서 정해진다
            assertThat(structured(result)).containsEntry("account_id", (int) p.leaderAccountId())
                    .containsEntry("project_id", (int) p.projectId())
                    .containsEntry("task_id", "501")
                    .containsEntry("count", 3)
                    .containsEntry("has_session", true)
                    .containsKey("agent_token_id");
            assertThat(text(result)).contains("\"task_id\":\"501\"");
        } finally {
            client.closeGracefully();
        }
    }

    /**
     * 깨지면: 업무 오류가 REST 와 다른 모양으로 나가 에이전트가 code 로 분기할 수 없다
     * (08 계약 1절 "ApiException 의 code·message 를 도구 실패 결과로", enum 변환 실패는 validation_failed).
     */
    @Test
    void errors_becomeToolFailureWithCommonErrorBody() {
        var p = fixtures().projectWithLeader();
        String token = issueToken(p.leaderEmail(), p.projectId());

        McpSyncClient client = connect(token);
        try {
            client.initialize();

            CallToolResult business = call(client, Map.of("mode", "API_ERROR"));
            assertThat(business.isError()).isTrue();
            assertThat(error(business)).containsEntry("code", "task_conflict")
                    .containsEntry("message", "다른 사람이 맡은 작업입니다");
            assertThat(json.readTree(text(business)).get("error").get("code").asString()).isEqualTo("task_conflict");

            CallToolResult badEnum = call(client, Map.of("mode", "NOPE"));
            assertThat(badEnum.isError()).isTrue();
            assertThat(error(badEnum)).containsEntry("code", "validation_failed")
                    .containsEntry("message", "mode 값이 올바르지 않습니다");

            CallToolResult badType = call(client, Map.of("count", "많이"));
            assertThat(error(badType)).containsEntry("code", "validation_failed")
                    .containsEntry("message", "count 값이 올바르지 않습니다");

            // ApiException 밖의 예외도 REST 대응표(GlobalExceptionHandler)와 같은 code 로 나간다
            CallToolResult lock = call(client, Map.of("mode", "LOCK_CONFLICT"));
            assertThat(error(lock)).containsEntry("code", "revision_conflict");

            // 인수 타입을 만들 수 없는 것은 서버 버그다 — 입력 탓(validation_failed)으로 숨기지 않는다
            CallToolResult broken = client.callTool(McpSchema.CallToolRequest.builder()
                    .name("broken_args").arguments(Map.of()).build());
            assertThat(error(broken)).containsEntry("code", "internal");
        } finally {
            client.closeGracefully();
        }
    }

    /**
     * 깨지면: GET /mcp 대기 스트림이 열려, 에이전트가 붙어 있는 동안 graceful shutdown 이 매 배포마다 30초를 다 기다린다
     * (Spring Boot Tomcat GracefulShutdown 은 진행 중인 async 요청이 있으면 끝나지 않는다 — 실측 함정).
     */
    @Test
    void listeningStreamGet_isClosedWith405_afterAuthentication() {
        var p = fixtures().projectWithLeader();
        String token = issueToken(p.leaderEmail(), p.projectId());
        ApiClient raw = client();

        HttpResponse<String> get = raw.get("/mcp", "Accept", "text/event-stream", "Authorization", "Bearer " + token);
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).hasValue("POST, DELETE");
        assertThat(json.readTree(get.body()).get("error").get("code").asString()).isEqualTo("method_not_allowed");
        // 서블릿의 기본 doHead 는 doGet 을 부른다 — HEAD 로 돌아 들어가지 못해야 한다
        HttpResponse<String> head = raw.send(raw.request("/mcp", "Accept", "text/event-stream",
                "Authorization", "Bearer " + token).method("HEAD", java.net.http.HttpRequest.BodyPublishers.noBody()));
        assertThat(head.statusCode()).isEqualTo(405);
        // 토큰 검사가 먼저다
        assertThat(raw.get("/mcp", "Accept", "text/event-stream").statusCode()).isEqualTo(401);
    }

    /** 깨지면: 토큰 없이·틀린 토큰으로 MCP 세션을 열거나, 세션을 연 뒤 폐기한 토큰으로 계속 도구를 부른다 (MCP 연결과 인증 "매 요청 토큰 폐기 확인"). */
    @Test
    void missingWrongOrRevokedToken_isRejectedWith401() {
        String initialize = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""";
        ApiClient raw = client();
        HttpResponse<String> none = raw.post("/mcp", initialize, "Accept", "application/json, text/event-stream");
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(json.readTree(none.body()).get("error").get("code").asString()).isEqualTo("unauthenticated");
        assertThat(none.headers().firstValue("X-Request-Id")).isPresent();

        HttpResponse<String> wrong = raw.post("/mcp", initialize, "Accept", "application/json, text/event-stream",
                "Authorization", "Bearer no-such-token");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(json.readTree(wrong.body()).get("error").get("code").asString()).isEqualTo("unauthenticated");

        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        JsonNode issued = json.readTree(leader.post("/api/projects/" + p.projectId() + "/agent-tokens", "{}").body());
        String token = issued.get("token").asString();
        McpSyncClient client = connect(token);
        try {
            client.initialize();
            assertThat(call(client, Map.of()).isError()).isFalse();

            leader.send(leader.request("/api/agent-tokens/" + issued.get("token_info").get("id").asString()).DELETE());

            assertThatThrownBy(() -> call(client, Map.of())).isInstanceOf(McpHttpClientTransportAuthorizationException.class);
        } finally {
            client.closeGracefully();
        }
    }

    /** 닫을 때는 closeGracefully() 를 쓴다 — 이유는 {@link McpTestClient} */
    private McpSyncClient connect(String token) {
        return McpTestClient.connect(Integer.parseInt(env.getRequiredProperty("local.server.port")), token);
    }

    private String issueToken(String email, long projectId) {
        ApiClient user = client().login(email, AccessFixtures.PASSWORD);
        return json.readTree(user.post("/api/projects/" + projectId + "/agent-tokens", "{}").body())
                .get("token").asString();
    }

    private static CallToolResult call(McpSyncClient client, Map<String, Object> args) {
        return client.callTool(McpSchema.CallToolRequest.builder().name("probe").arguments(args).build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(CallToolResult result) {
        return (Map<String, Object>) structured(result).get("error");
    }

    private static String text(CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }
}
