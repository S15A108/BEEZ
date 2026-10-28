package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import org.junit.jupiter.api.Test;

/** Q04 제안 정책의 경계. 72바이트 상한은 bcrypt 가 넘는 부분을 조용히 버리는 함정 때문이다. */
class PasswordPolicyTest {

    @Test
    void lengthBoundary_is8Chars() {
        assertThatThrownBy(() -> PasswordPolicy.check("abcd123")).isInstanceOf(ApiException.class);
        assertThatCode(() -> PasswordPolicy.check("abcd1234")).doesNotThrowAnyException();
    }

    @Test
    void needsBothLatinLetterAndDigit() {
        assertThatThrownBy(() -> PasswordPolicy.check("abcdefgh")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("12345678")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("비밀번호12345")).isInstanceOf(ApiException.class);
    }

    @Test
    void upperBound_isCountedInUtf8Bytes_notChars() {
        String hangul23 = "가".repeat(23);   // 3바이트 × 23 = 69
        assertThatCode(() -> PasswordPolicy.check("a1" + hangul23)).doesNotThrowAnyException();        // 71바이트
        assertThatThrownBy(() -> PasswordPolicy.check("a1" + hangul23 + "가")).isInstanceOf(ApiException.class); // 74바이트, 26자
    }
}
