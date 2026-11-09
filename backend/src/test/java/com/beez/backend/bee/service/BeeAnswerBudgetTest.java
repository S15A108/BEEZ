package com.beez.backend.bee.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** BEE-A01 재시도가 Nginx 읽기 대기(180초)를 넘기지 않게 막는 시간 예산 (S15P31A108-344) */
class BeeAnswerBudgetTest {

    private static final Duration CHAT = Duration.ofSeconds(70);

    @Test
    void retriesWhenTheFirstAttemptWasFast() {
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(40).toNanos(), CHAT)).isTrue();
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(80).toNanos(), CHAT)).isTrue();
    }

    @Test
    void skipsTheRetryWhenAnotherFullCallWouldPassTheBudget() {
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(81).toNanos(), CHAT)).isFalse();
        assertThat(BeeQuestionService.retryFits(Duration.ofSeconds(95).toNanos(), CHAT)).isFalse();
    }

    @Test
    void budgetStaysBelowTheProxyReadTimeout() {
        // infra/nginx 의 비비 질문 경로 proxy_read_timeout 은 180초다. 예산이 그보다 짧아야 브라우저가 먼저 끊지 않는다
        assertThat(BeeQuestionService.ANSWER_BUDGET).isLessThan(Duration.ofSeconds(180));
    }
}
