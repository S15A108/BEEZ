package com.beez.backend.auth.agent;

import com.beez.backend.common.access.AgentPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /mcp 체인 전용. Bearer 토큰을 {@link AgentTokenVerifier} 로 검증해 {@link AgentPrincipal} 을 SecurityContext 에 넣는다.
 * 토큰이 없으면 그냥 통과시키고 인가 단계에서 401 이 난다.
 * 서블릿 필터로 자동 등록되면 모든 경로에 걸리므로 @Component 로 만들지 않고 SecurityConfig 에서 직접 생성한다.
 */
public class AgentTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final AgentTokenVerifier verifier;
    private final AuthenticationEntryPoint entryPoint;

    public AgentTokenAuthenticationFilter(AgentTokenVerifier verifier, AuthenticationEntryPoint entryPoint) {
        this.verifier = verifier;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(req, res);
            return;
        }
        AgentPrincipal principal;
        try {
            principal = verifier.verify(header.substring(BEARER.length()).trim());
        } catch (AgentTokenException e) {
            SecurityContextHolder.clearContext();
            entryPoint.commence(req, res, e);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
        chain.doFilter(req, res);
    }
}
