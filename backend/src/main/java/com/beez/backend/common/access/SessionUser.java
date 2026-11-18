package com.beez.backend.common.access;

import java.io.Serializable;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 웹 세션에 저장되는 인증 주체. 계정 ID 만 담는다 — 이름·역할은 세션에 두면 낡으므로 매 요청 DB 에서 읽는다.
 * HttpSession 에 직렬화되어 들어가므로 Serializable.
 * <p>
 * {@link #getName()} 이 계정 ID 라서 Spring Session 이 세션 행의 principal_name 에 계정 ID 를 적는다 (-242).
 * 그래서 {@code FindByIndexNameSessionRepository.findByPrincipalName(String.valueOf(accountId))} 로
 * 그 사용자의 세션을 모두 찾아 끊을 수 있다 (-171 탈퇴·비밀번호 변경).
 */
public record SessionUser(long accountId) implements AuthenticatedPrincipal, Serializable {

    @Override
    public String getName() {
        return Long.toString(accountId);
    }
}
