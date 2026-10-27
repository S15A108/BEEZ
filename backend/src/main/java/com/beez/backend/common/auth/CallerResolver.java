package com.beez.backend.common.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 요청에서 {@link CallerContext} 를 꺼내는 역할.
 *
 * <p>구현은 하나만 빈으로 떠야 한다: {@link SecurityCallerResolver} (-170). 사람 세션·에이전트 토큰 인증은
 * Spring Security 필터가 끝낸 상태이고, 이 구현은 그 결과를 {@link CallerContext} 로 옮기기만 한다.
 */
public interface CallerResolver {

    /**
     * @return 인증된 호출자. 인증 정보가 없으면 401 {@code unauthenticated} 예외를 던진다.
     */
    CallerContext resolve(HttpServletRequest request);
}
