package com.beez.backend.common.auth;

import com.beez.backend.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 임시 호출자 식별 — 요청 헤더로 계정을 지정한다. <b>local·test 프로필에서만</b> 빈으로 뜬다.
 *
 * <pre>
 * X-Beez-Account-Id: 3            (필수) 계정 ID
 * X-Beez-Agent-Token-Id: 7        (선택) 있으면 에이전트 호출로 본다
 * </pre>
 *
 * 인증(S15P31A108-68)이 들어오면 이 클래스는 삭제하고 Spring Security 기반 구현으로 바꾼다.
 */
@Component
@Profile({"local", "test"})
public class HeaderCallerResolver implements CallerResolver {

    public static final String ACCOUNT_HEADER = "X-Beez-Account-Id";
    public static final String AGENT_TOKEN_HEADER = "X-Beez-Agent-Token-Id";

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        String accountHeader = request.getHeader(ACCOUNT_HEADER);
        if (accountHeader == null || accountHeader.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "인증 정보가 없습니다");
        }
        Long accountId = parseId(accountHeader);

        String tokenHeader = request.getHeader(AGENT_TOKEN_HEADER);
        if (tokenHeader == null || tokenHeader.isBlank()) {
            return CallerContext.human(accountId);
        }
        Long tokenId = parseId(tokenHeader);
        return CallerContext.agent(accountId, tokenId);
    }

    private Long parseId(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "인증 정보 형식이 올바르지 않습니다");
        }
    }
}
