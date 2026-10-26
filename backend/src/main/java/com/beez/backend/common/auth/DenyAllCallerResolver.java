package com.beez.backend.common.auth;

import com.beez.backend.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 운영(prod) 프로필용 임시 구현 — 인증(S15P31A108-68)이 들어오기 전까지 <b>모든 요청을 401 로 거절</b>한다.
 * 헤더 방식(HeaderCallerResolver)이 운영에 올라가는 것을 막으면서도 서버 기동은 되게 한다 (배포 파이프라인이 깨지지 않도록).
 * 인증 구현이 들어오면 이 클래스는 삭제한다.
 */
@Component
@Profile("prod")
public class DenyAllCallerResolver implements CallerResolver {

    @Override
    public CallerContext resolve(HttpServletRequest request) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "인증이 아직 연결되지 않았습니다");
    }
}
