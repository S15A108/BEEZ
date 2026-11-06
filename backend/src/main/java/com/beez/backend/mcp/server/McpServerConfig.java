package com.beez.backend.mcp.server;

import com.beez.backend.agenttoken.AgentSessionService;
import com.beez.backend.agenttoken.AgentTokenService;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.api.ApiErrorResponse;
import com.beez.backend.common.web.RequestIdFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * MCP 서버 (-176). Claude Code 가 붙는 {@code /mcp} 를 MCP Java SDK 의 Streamable HTTP 서블릿으로 연다.
 *
 * <p>요청 순서: RequestIdFilter(요청 ID) → SecurityConfig.mcpChain(Bearer 토큰 검증, 실패 401) → 이 서블릿.
 * 서블릿은 DispatcherServlet 과 따로 등록되어 @RestControllerAdvice 를 타지 않는다 — 도구 오류는 {@link McpToolHandler} 가 결과로 바꾼다.
 * 세션은 서버 메모리에 있다(재시작하면 클라이언트가 다시 initialize 한다). GET 대기 스트림은 열지 않는다({@link #mcpListeningStreamBlocker}). 설명은 docs/design/mcp-server-S15P31A108-176.md.
 */
@Configuration
public class McpServerConfig {

    static final String ENDPOINT = "/mcp";
    static final String SERVLET_NAME = "mcp";
    /** 한 요청 본문 상한. REST 의 요청 전체 2 MiB(spring.servlet.multipart)와 맞춘다. SDK 기본은 16 MiB */
    static final int REQUEST_MAX_BYTES = 2 * 1024 * 1024;

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(McpServerConfig::extractContext)
                .maxRequestSize(REQUEST_MAX_BYTES)
                // 서버가 먼저 보내는 메시지 통로(GET 스트림)를 열지 않으므로 ping 을 보낼 곳이 없다
                .keepAliveInterval(null)
                // GET 스트림이 없으면 SDK 정리기가 "두 정리 사이에 요청이 없던" 세션을 닫는다(닫힌 세션의 다음 요청은 404 → 클라이언트 재연결).
                // 기본 30분이면 점심만 다녀와도 끊기므로, 웹 세션(마지막 요청 후 2시간)과 비슷하게 마지막 요청 후 1~2시간 두도록 1시간 주기로 늘린다
                .sessionSweepInterval(Duration.ofHours(1))
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<>(transport, ENDPOINT);
        registration.setName(SERVLET_NAME);
        registration.setAsyncSupported(true);   // POST 응답도 SSE 로 흘려 보낸다
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * GET·HEAD /mcp(서버 → 클라이언트 대기 스트림)는 405 로 닫는다. MCP 명세상 서버가 고를 수 있고, 클라이언트는 POST 만으로 동작한다.
     * 열어 두면 끝나지 않는 비동기 요청이 되어 graceful shutdown 이 매 배포마다 대기 시간(30초)을 다 채운다.
     * 서버가 먼저 보낼 기능(알림·elicitation)이 생기면 그때 종료 처리와 함께 연다.
     * 보안 필터 뒤에서 돌아 토큰 없는 GET 은 여전히 401 이다.
     */
    @Bean
    FilterRegistrationBean<OncePerRequestFilter> mcpListeningStreamBlocker(ObjectMapper json) {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                // HEAD 도 막는다 — HttpServlet.doHead 는 doGet 을 그대로 불러 같은 대기 스트림을 연다
                if (!HttpMethod.GET.matches(req.getMethod()) && !HttpMethod.HEAD.matches(req.getMethod())) {
                    chain.doFilter(req, res);
                    return;
                }
                res.setStatus(HttpStatus.METHOD_NOT_ALLOWED.value());
                res.setHeader(HttpHeaders.ALLOW, "POST, DELETE");
                res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                res.setCharacterEncoding("UTF-8");
                json.writeValue(res.getWriter(),
                        ApiErrorResponse.of("method_not_allowed", "지원하지 않는 HTTP 메서드입니다"));
            }
        };
        var registration = new FilterRegistrationBean<>(filter);
        registration.addServletNames(SERVLET_NAME);
        return registration;
    }

    /** 연결 기록·세션↔토큰 결속·1인 1에이전트 (-178). 보안 필터(토큰 검증) 뒤에서 돈다 */
    @Bean
    FilterRegistrationBean<McpSessionFilter> mcpSessionFilter(AgentSessionService sessions, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new McpSessionFilter(sessions, json));
        registration.addServletNames(SERVLET_NAME);
        return registration;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                            ObjectProvider<McpTool<?>> tools,   // 도구가 하나도 없어도(-179 전) 기동한다
                            McpToolHandler handler, ObjectProvider<BuildProperties> build) {
        Set<String> names = new HashSet<>();
        List<SyncToolSpecification> specs = new ArrayList<>();
        tools.orderedStream().forEach(tool -> {
            if (!names.add(tool.name())) {
                throw new IllegalStateException("MCP 도구 이름이 겹칩니다: " + tool.name());
            }
            specs.add(handler.specification(tool));
        });
        BuildProperties info = build.getIfAvailable();
        return McpServer.sync(transport)
                .serverInfo("beez", info == null ? "dev" : info.getVersion())
                // 작업 절차 안내(AGT-04). initialize 응답으로 클라이언트에 간다 — 토큰 발급 응답의 claude_md_snippet 과 같은 글
                .instructions(AgentTokenService.CLAUDE_MD_SNIPPET)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                // 서블릿 전송은 요청 스레드에서 막고 기다리므로 다른 스레드로 넘길 이유가 없다 (SDK 권장: 블로킹 전송이면 true)
                .immediateExecution(true)
                // 스키마 검사 실패를 SDK 문구(text)로 내지 않고, 인수 변환·서비스 검증의 validation_failed 로 통일한다
                .validateToolInputs(false)
                .tools(specs)
                .build();
    }

    /**
     * 요청 스레드에서 필터가 넣어 둔 인증 주체와 요청 ID 를 꺼내 도구 처리로 넘긴다.
     * SDK 가 처리 스레드를 바꿔도 ThreadLocal(SecurityContext·MDC)에 기대지 않으려는 것이다.
     */
    static McpTransportContext extractContext(HttpServletRequest request) {
        Map<String, Object> values = new HashMap<>();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AgentPrincipal principal) {
            values.put(McpToolHandler.PRINCIPAL_KEY, principal);
        }
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            values.put(McpToolHandler.REQUEST_ID_KEY, requestId);
        }
        return McpTransportContext.create(values);
    }
}
