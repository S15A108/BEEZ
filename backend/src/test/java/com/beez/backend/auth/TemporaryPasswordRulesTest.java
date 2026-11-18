package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.beez.backend.auth.recovery.PasswordRecoveryService;
import com.beez.backend.auth.recovery.TemporaryPassword;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 임시 비밀번호(ACC-A07) 규칙. 판정은 시각을 받으므로 DB 없이 본다. */
class TemporaryPasswordRulesTest {

    /**
     * 깨지면: 메일로 보낸 임시 비밀번호가 비밀번호 정책(가입·변경과 같은 규칙)을 어겨, 그것으로 로그인한 사람이
     * 비밀번호 변경(ACC-A06) 때 현재 비밀번호로는 통하는데 같은 값을 새 비밀번호로 쓸 수 없는 등 정책과 어긋난다 (명세 "ACC-A01 규칙을 만족").
     */
    @Test
    void generated_alwaysSatisfiesPasswordPolicy() {
        for (int i = 0; i < 2000; i++) {
            String p = PasswordRecoveryService.generate();
            assertThat(p).hasSize(12);
            assertThatCode(() -> PasswordPolicy.check(p)).doesNotThrowAnyException();
        }
    }

    /** 깨지면: 만료 시각이 지났거나 닫힌(사용·재발급·발송 실패) 임시 비밀번호, 계정이 맞지 않은 요청 행으로 로그인된다 (완료 조건 "재사용·만료 거부"). */
    @Test
    void usableOnlyWhileOpenAndBeforeExpiry() {
        Instant now = Instant.parse("2026-11-04T00:00:00Z");
        TemporaryPassword t = TemporaryPassword.issue("a@example.com", 1L, "{bcrypt}x", now.plusSeconds(1800));
        assertThat(t.usableAt(now)).isTrue();
        assertThat(t.usableAt(now.plusSeconds(1800))).as("만료 시각부터는 못 쓴다").isFalse();

        t.use(now);
        assertThat(t.usableAt(now)).as("한 번 쓰면 닫힌다").isFalse();

        assertThat(TemporaryPassword.unmatched("b@example.com", now).usableAt(now.minusSeconds(1))).isFalse();
    }
}
