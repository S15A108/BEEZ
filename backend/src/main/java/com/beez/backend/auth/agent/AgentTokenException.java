package com.beez.backend.auth.agent;

import org.springframework.security.core.AuthenticationException;

/** 에이전트 토큰 인증 실패. code 는 응답 error.code 로 나간다 (unauthenticated, token_revoked). */
public class AgentTokenException extends AuthenticationException {

    private final String code;

    public AgentTokenException(String code, String detail) {
        super(detail);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
