package com.beez.backend.common.auth;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.access.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Spring Security 가 인증한 주체를 {@link CallerContext} 로 옮긴다.
 * <ul>
 *   <li>/api/** 세션 → {@link SessionUser} → {@code CallerContext.human}</li>
 *   <li>/mcp 토큰 → {@link AgentPrincipal} → {@code CallerContext.agent}</li>
 * </ul>
 * MCP 어댑터(-176)처럼 컨트롤러 인자 주입을 쓰지 않는 곳은 {@link #current()} 를 부른다.
 */
@Component
public class SecurityCallerResolver implements CallerResolver {

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        return current();
    }

    /** 현재 요청 스레드의 호출자. 인증 정보가 없으면 401. */
    public static CallerContext current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof SessionUser user) {
            return CallerContext.human(user.accountId());
        }
        if (principal instanceof AgentPrincipal agent) {
            return CallerContext.agent(agent.accountId(), agent.agentTokenId());
        }
        throw AccessErrors.unauthenticated();
    }
}
