package com.beez.backend.common.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP 요청에서 {@link CallerContext} 를 꺼내는 역할.
 *
 * <p>구현은 하나만 빈으로 떠야 한다.
 * <ul>
 *   <li>지금: {@link HeaderCallerResolver} — local·test 프로필 전용 임시 구현</li>
 *   <li>인증(S15P31A108-68) 이후: Spring Security 세션·토큰에서 읽는 구현으로 교체</li>
 * </ul>
 * 운영 프로필에 구현이 없으면 컨트롤러 인자 주입에 실패해 기동이 멈춘다. 임시 구현이 운영으로 새는 것을 막는 장치다.
 */
public interface CallerResolver {

    /**
     * @return 인증된 호출자. 인증 정보가 없으면 401 {@code unauthenticated} 예외를 던진다.
     */
    CallerContext resolve(HttpServletRequest request);
}
