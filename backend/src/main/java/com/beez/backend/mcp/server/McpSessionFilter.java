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
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 연결 세션 (S15P31A108-178). 토큰 검증(mcpChain) 뒤, MCP 서블릿 앞에서 돈다.
 * SDK 2.1.0 에는 서버가 initialize 를 받아 볼 훅이 없어(도구 필터도 transport context 만 받는다) 서블릿 앞에서 본다.
 * <ul>
 *   <li>세션 ID 없는 POST = initialize: 요청 본문과 응답을 붙잡아 두었다가, 응답이 새 Mcp-Session-Id 를 주면 clientInfo 와 함께 연결을 기록한 뒤에
 *       응답을 내보낸다. 클라이언트가 세션 ID 를 받기 전에 기록이 커밋되므로, 바로 이어지는 요청이 "기록 없음"으로 막히지 않는다</li>
 *   <li>세션 ID 있는 요청: 기록이 없거나·다른 토큰의 세션이거나·이미 닫힌 세션이면 같은 404 {@code not_found}.
 *       기록이 없는 ID 는 SDK 까지 가지 않으므로, 남의 세션과 없는 세션의 응답이 같다. 같은 계정의 새 연결에 밀려났으면 409 {@code agent_session_replaced} —
 *       404 로 주면 클라이언트가 조용히 다시 initialize 해 두 클라이언트가 서로 끝없이 밀어낸다</li>
 *   <li>DELETE: 밀려난 세션이어도 SDK 까지 보내 서버 메모리의 세션을 지우게 하고, 성공하면 연결을 끝낸다</li>
 * </ul>
 * 세션 확인·기록이 DB 오류로 실패하면 500 이다(fail-closed) — 결속을 건너뛴 채 통과시키지 않는다.
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

        Admission admission;
        try {
            admission = sessions.admit(principal, sessionKey);
        } catch (RuntimeException e) {
            log.error("mcp session admit failed", e);
            internal(res);
            return;
        }
        if (admission == Admission.NOT_FOUND) {
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
            try {
                sessions.close(principal, sessionKey);
            } catch (RuntimeException e) {
                // SDK 는 이미 세션을 지웠다. 기록만 남는다 — 다음 요청은 SDK 가 404 로 막는다
                log.error("mcp session close failed", e);
            }
        }
    }

    /**
     * initialize 는 응답을 붙잡아 둔다. SDK 는 initialize 를 요청 스레드에서 JSON 한 번으로 응답한다(SSE·비동기 아님) —
     * 그래서 체인이 돌아온 뒤 헤더를 읽고 기록한 다음 내보낼 수 있다.
     */
    private void initialize(HttpServletRequest req, HttpServletResponse res, FilterChain chain,
                            AgentPrincipal principal) throws ServletException, IOException {
        var body = new ContentCachingRequestWrapper(req, McpServerConfig.REQUEST_MAX_BYTES);
        var held = new ContentCachingResponseWrapper(res);
        chain.doFilter(body, held);
        // SDK 는 initialize 에만 새 세션 ID 를 응답 헤더로 준다. 다른 세션 없는 POST(잘못된 요청)는 헤더가 없다
        String created = held.getHeader(SESSION_HEADER);
        if (created != null && held.getStatus() < 300) {
            JsonNode clientInfo = clientInfo(body.getContentAsByteArray());
            try {
                sessions.connect(principal, created, text(clientInfo, "name"), text(clientInfo, "version"));
            } catch (RuntimeException e) {
                // 기록 없는 세션은 다음 요청부터 404 라 어차피 못 쓴다. 성공으로 알리지 않는다
                // (SDK 메모리의 세션은 정리 주기에 사라진다)
                log.error("mcp session connect failed", e);
                held.reset();
                internal(held);
            }
        }
        held.copyBodyToResponse();
    }

    private JsonNode clientInfo(byte[] body) {
        try {
            return json.readTree(body).path("params").path("clientInfo");
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

    private void internal(HttpServletResponse res) throws IOException {
        reject(res, HttpStatus.INTERNAL_SERVER_ERROR, "internal", "서버 내부 오류");
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
