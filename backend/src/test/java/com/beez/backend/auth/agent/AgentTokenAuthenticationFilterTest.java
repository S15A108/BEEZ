package com.beez.backend.auth.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.access.AgentPrincipal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/** Authorization 스킴 이름은 대소문자를 구분하지 않는다 (RFC 9110 11.1). */
class AgentTokenAuthenticationFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void lowercaseBearerScheme_isStillReadAsAgentToken() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        AgentTokenAuthenticationFilter filter = new AgentTokenAuthenticationFilter(raw -> {
            seen.set(raw);
            return new AgentPrincipal(1L, 2L, 3L);
        }, (req, res, e) -> res.setStatus(401));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/mcp");
        req.addHeader("Authorization", "bearer tok-123");
        AtomicReference<Object> principal = new AtomicReference<>();

        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest r, jakarta.servlet.ServletResponse s) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                principal.set(auth == null ? null : auth.getPrincipal());
            }
        });

        assertThat(seen.get()).isEqualTo("tok-123");
        assertThat(principal.get()).isEqualTo(new AgentPrincipal(1L, 2L, 3L));
    }
}
