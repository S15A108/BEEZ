package com.beez.backend.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.auth.verification.EmailVerification.Outcome;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 인증 코드·가입 토큰 판정 규칙 (ACC-A11·A12·A01, -219 완료 조건 1·2). DB 없이 시각을 넣어 본다. */
class EmailVerificationTest {

    private static final int MAX = 5;
    private static final Instant T0 = Instant.parse("2026-11-04T03:00:00Z");

    private static EmailVerification issued() {
        return EmailVerification.issue("a@example.com", "hash", T0.plus(Duration.ofMinutes(5)));
    }

    // 깨지면: 유효 5분이 지난 코드로 인증이 통과한다 (완료 조건 "만료 코드 거부")
    @Test
    void 만료_시각부터는_EXPIRED() {
        EmailVerification v = issued();
        assertThat(v.rejectBeforeMatch(T0.plus(Duration.ofMinutes(5)).minusMillis(1), MAX)).isNull();
        assertThat(v.rejectBeforeMatch(T0.plus(Duration.ofMinutes(5)), MAX)).isEqualTo(Outcome.EXPIRED);
    }

    // 깨지면: 한 번 확인한 코드로 토큰을 또 받을 수 있다 (완료 조건 "재사용 코드 거부")
    @Test
    void 확인에_성공한_코드는_다시_쓸_수_없다() {
        EmailVerification v = issued();
        v.confirm(T0, "token-hash", T0.plus(Duration.ofMinutes(30)));
        assertThat(v.rejectBeforeMatch(T0.plusSeconds(1), MAX)).isEqualTo(Outcome.EXPIRED);
    }

    // 깨지면: 틀린 횟수 한도가 하나 어긋나거나, 한도를 넘긴 뒤 맞는 코드로 통과한다 (완료 조건 "시도 횟수 초과 거부")
    @Test
    void 한도만큼_틀리면_TOO_MANY_ATTEMPTS_이고_그_뒤에도_계속_거부() {
        EmailVerification v = issued();
        for (int i = 1; i < MAX; i++) {
            assertThat(v.recordMismatch(T0, MAX)).isEqualTo(Outcome.MISMATCH);
            assertThat(v.remainingAttempts(MAX)).isEqualTo(MAX - i);
        }
        assertThat(v.recordMismatch(T0, MAX)).isEqualTo(Outcome.TOO_MANY_ATTEMPTS);
        // 닫힌 요청이지만 410 이 아니라 계속 429 — 화면이 "코드를 다시 받으세요"를 같은 문구로 보여 준다
        assertThat(v.rejectBeforeMatch(T0.plusSeconds(1), MAX)).isEqualTo(Outcome.TOO_MANY_ATTEMPTS);
    }

    // 깨지면: 남의 이메일로 받은 토큰, 만료·소비된 토큰으로 가입된다 (완료 조건 "토큰 이메일과 가입 이메일 불일치 거부")
    @Test
    void 토큰은_같은_이메일_만료_전_한_번만_쓸_수_있다() {
        EmailVerification v = issued();
        Instant tokenExpires = T0.plus(Duration.ofMinutes(30));
        v.confirm(T0, "token-hash", tokenExpires);

        assertThat(v.tokenUsableFor("a@example.com", T0)).isTrue();
        assertThat(v.tokenUsableFor("b@example.com", T0)).isFalse();
        assertThat(v.tokenUsableFor("a@example.com", tokenExpires)).isFalse();

        v.consumeToken(T0);
        assertThat(v.tokenUsableFor("a@example.com", T0)).isFalse();
    }
}
