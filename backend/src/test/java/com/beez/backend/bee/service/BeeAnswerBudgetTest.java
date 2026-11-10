package com.beez.backend.bee.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** BEE-A01 재시도가 Nginx 기본 읽기 대기(60초)를 넘기지 않게 막는 시간 예산 (S15P31A108-344) */
class BeeAnswerBudgetTest {

    private static final Duration CHAT = Duration.ofSeconds(35);
    private static final Duration BUDGET = BeeQuestionService.DEFAULT_ANSWER_BUDGET;

    @Test
    void retriesOnlyWhenTheFirstStepsWereFast() {
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(5).toNanos(), CHAT, BUDGET)).isTrue();
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(20).toNanos(), CHAT, BUDGET)).isTrue();
    }

    @Test
    void skipsTheRetryWhenAnotherFullCallWouldPassTheBudget() {
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(21).toNanos(), CHAT, BUDGET)).isFalse();
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(40).toNanos(), CHAT, BUDGET)).isFalse();
    }

    @Test
    void defaultBudgetStaysBelowNginxDefaultReadTimeout() {
        // 서버 Nginx 의 /api/ 는 proxy_read_timeout 이 없어 기본 60초다. 예산은 그보다 짧아야 브라우저가 먼저 끊지 않는다
        assertThat(BUDGET).isLessThan(Duration.ofSeconds(60));
    }

}
