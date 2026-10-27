package com.beez.backend.common.auth;

import com.beez.backend.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Server-owned identity bridge for the login module. Never accepts account headers or body IDs. */
@Component
public class SessionAccountIdentity {
    public static final String ACCOUNT_ATTRIBUTE = "beez.authenticatedAccountId";
    public static final String CSRF_ATTRIBUTE = "beez.csrfToken";
    private final SecureRandom random = new SecureRandom();

    public CallerContext require(HttpServletRequest request, boolean mutation) {
        HttpSession session = request.getSession(false);
        Long accountId = session != null && session.getAttribute(ACCOUNT_ATTRIBUTE) instanceof Long value ? value : null;
        if (accountId == null && request.getUserPrincipal() != null) {
            try { accountId = Long.valueOf(request.getUserPrincipal().getName()); }
            catch (NumberFormatException ignored) { /* Principal contract: account primary key. */ }
        }
        if (accountId == null || accountId <= 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "로그인이 필요합니다.");
        }
        if (mutation) {
            Object expected = session == null ? null : session.getAttribute(CSRF_ATTRIBUTE);
            String supplied = request.getHeader("X-CSRF-Token");
            if (!(expected instanceof String token) || supplied == null || !MessageDigest.isEqual(
                    token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.FORBIDDEN, "csrf_failed", "CSRF 토큰을 확인하세요.");
            }
        }
        return CallerContext.human(accountId);
    }

    public String csrfToken(HttpServletRequest request) {
        require(request, false);
        HttpSession session = request.getSession(true);
        synchronized (session) {
            if (session.getAttribute(CSRF_ATTRIBUTE) instanceof String existing) return existing;
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(CSRF_ATTRIBUTE, token);
            return token;
        }
    }
}
