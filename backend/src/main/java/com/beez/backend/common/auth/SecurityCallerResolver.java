package com.beez.backend.common.auth;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.access.SessionUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
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
        return find().orElseThrow(AccessErrors::unauthenticated);
    }

    /** 현재 요청 스레드의 호출자. PUBLIC 경로처럼 로그인이 선택인 곳에서 쓴다 (초대 미리보기 MEM-A04). */
    public static Optional<CallerContext> find() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth == null ? null : auth.getPrincipal();
        if (principal instanceof SessionUser user) {
            return Optional.of(CallerContext.human(user.accountId()));
        }
        if (principal instanceof AgentPrincipal agent) {
            return Optional.of(CallerContext.agent(agent.accountId(), agent.agentTokenId()));
        }
        return Optional.empty();
    }
}
