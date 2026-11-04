package com.beez.backend.mcp.server;

import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.web.RequestIdFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * MCP 서버 (-176). Claude Code 가 붙는 {@code /mcp} 를 MCP Java SDK 의 Streamable HTTP 서블릿으로 연다.
 *
 * <p>요청 순서: RequestIdFilter(요청 ID) → SecurityConfig.mcpChain(Bearer 토큰 검증, 실패 401) → 이 서블릿.
 * 서블릿은 DispatcherServlet 과 따로 등록되어 @RestControllerAdvice 를 타지 않는다 — 도구 오류는 {@link McpToolHandler} 가 결과로 바꾼다.
 * 세션은 서버 메모리에 있다(재시작하면 클라이언트가 다시 initialize 한다). 설명은 docs/design/mcp-server-S15P31A108-176.md.
 */
@Configuration
public class McpServerConfig {

    static final String ENDPOINT = "/mcp";
    /** 한 요청 본문 상한. REST 의 요청 전체 2 MiB(spring.servlet.multipart)와 맞춘다. SDK 기본은 16 MiB */
    static final int REQUEST_MAX_BYTES = 2 * 1024 * 1024;

    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(ENDPOINT)
                .contextExtractor(McpServerConfig::extractContext)
                .maxRequestSize(REQUEST_MAX_BYTES)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        var registration = new ServletRegistrationBean<>(transport, ENDPOINT);
        registration.setName("mcp");
        registration.setAsyncSupported(true);   // 요청마다 SSE 응답 스트림을 연다
        registration.setLoadOnStartup(1);
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
