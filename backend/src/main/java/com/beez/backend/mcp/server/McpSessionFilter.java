package com.beez.backend.mcp.server;

import com.beez.backend.agenttoken.AgentSessionService;
import com.beez.backend.agenttoken.AgentSessionService.Admission;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.api.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 연결 세션 (S15P31A108-178). 토큰 검증(mcpChain) 뒤, MCP 서블릿 앞에서 돈다.
 * SDK 2.1.0 에는 서버가 initialize 를 받아 볼 훅이 없어(도구 필터도 transport context 만 받는다) 서블릿 앞에서 본다.
 * <ul>
 *   <li>세션 ID 없는 POST = initialize: 요청 본문을 남겨 두었다가, 응답이 새 Mcp-Session-Id 를 주면 clientInfo 와 함께 연결을 기록한다</li>
 *   <li>세션 ID 있는 요청: 다른 토큰의 세션이면 404(없는 세션과 같게 — 남의 세션 존재를 알리지 않는다),
 *       같은 계정의 새 연결에 밀려났으면 409 {@code agent_session_replaced}.
 *       404 로 주면 클라이언트가 조용히 다시 initialize 해 두 클라이언트가 서로 끝없이 밀어낸다</li>
 *   <li>DELETE: 밀려난 세션이어도 SDK 까지 보내 서버 메모리의 세션을 지우게 하고, 성공하면 연결을 끝낸다</li>
 * </ul>
 * 세션 기록이 실패해도 MCP 요청은 그대로 처리한다 — 기록은 관측이고, 접근 제어는 토큰 검증과 서비스가 한다.
 */
class McpSessionFilter extends OncePerRequestFilter {

    static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final Logger log = LoggerFactory.getLogger("com.beez.mcp");

    private final AgentSessionService sessions;
    private final ObjectMapper json;

    McpSessionFilter(AgentSessionService sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        AgentPrincipal principal = principal();
        if (principal == null) {
            // mcpChain 이 인증을 요구하므로 정상 경로에서는 오지 않는다
            chain.doFilter(req, res);
            return;
        }
        String sessionKey = req.getHeader(SESSION_HEADER);
        boolean delete = HttpMethod.DELETE.matches(req.getMethod());

        if (sessionKey == null) {
            if (HttpMethod.POST.matches(req.getMethod())) {
                initialize(req, res, chain, principal);
            } else {
                chain.doFilter(req, res);
            }
            return;
        }

        Admission admission = admit(principal, sessionKey);
        if (admission == Admission.FOREIGN) {
            reject(res, HttpStatus.NOT_FOUND, "not_found", "MCP 세션이 없습니다. 다시 연결하세요");
            return;
        }
        if (admission == Admission.REPLACED && !delete) {
            reject(res, HttpStatus.CONFLICT, "agent_session_replaced",
                    "같은 계정이 이 프로젝트에 새로 연결해 이 연결은 끊겼습니다. 한 사람은 프로젝트마다 에이전트 하나만 연결할 수 있습니다");
            return;
        }
        chain.doFilter(req, res);
        if (delete && res.getStatus() < 300) {
            record(() -> sessions.close(principal, sessionKey), "close");
        }
    }

    private void initialize(HttpServletRequest req, HttpServletResponse res, FilterChain chain,
                            AgentPrincipal principal) throws ServletException, IOException {
        var cached = new ContentCachingRequestWrapper(req, McpServerConfig.REQUEST_MAX_BYTES);
        chain.doFilter(cached, res);
        // SDK 는 initialize 에만 새 세션 ID 를 응답 헤더로 준다. 다른 세션 없는 POST(잘못된 요청)는 헤더가 없다
        String created = res.getHeader(SESSION_HEADER);
        if (created == null || res.getStatus() >= 300) {
            return;
        }
        JsonNode clientInfo = clientInfo(cached.getContentAsByteArray());
        record(() -> sessions.connect(principal, created, text(clientInfo, "name"), text(clientInfo, "version")),
                "connect");
    }

    private Admission admit(AgentPrincipal principal, String sessionKey) {
        try {
            return sessions.admit(principal, sessionKey);
        } catch (RuntimeException e) {
            log.error("mcp session admit failed", e);
            return Admission.UNTRACKED;
        }
    }

    private void record(Runnable action, String what) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("mcp session {} failed", what, e);
        }
    }

    private JsonNode clientInfo(byte[] body) {
        try {
            JsonNode params = json.readTree(body).path("params");
            return params.path("clientInfo");
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private void reject(HttpServletResponse res, HttpStatus status, String code, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getWriter(), ApiErrorResponse.of(code, message));
    }

    private static AgentPrincipal principal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AgentPrincipal p ? p : null;
    }
}
