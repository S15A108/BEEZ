package com.beez.backend.auth;

import com.beez.backend.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;

/**
 * 비밀번호 정책 (Q04 제안안): 8자 이상, UTF-8 72바이트 이하, 영문·숫자 각 1자 이상.
 * 72바이트는 bcrypt 가 실제로 읽는 길이다. 넘는 부분은 무시되어 다른 비밀번호가 같은 해시가 된다.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 8;
    static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    static void check(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            throw invalid("비밀번호는 " + MIN_LENGTH + "자 이상이어야 합니다");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("비밀번호가 너무 깁니다 (UTF-8 " + MAX_BYTES + "바이트 이하)");
        }
        boolean hasLetter = password.chars().anyMatch(c -> (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'));
        boolean hasDigit = password.chars().anyMatch(c -> c >= '0' && c <= '9');
        if (!hasLetter || !hasDigit) {
            throw invalid("비밀번호는 영문과 숫자를 모두 포함해야 합니다");
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "password: " + message);
    }
}
