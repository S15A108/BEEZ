package com.beez.backend.context.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DevelopmentContextLimiterTest {

    /**
     * 깨지면: 실패에 반복 호출하는 에이전트가 MCP-01 유료 임베딩과 요청 스레드를 한없이 쓰거나, 한 에이전트의 한도가
     * 다른 에이전트·다른 프로젝트 호출까지 막는다 (-399 결정: (에이전트 토큰, 프로젝트)마다 1분 6회, 넘으면 429 rate_limited).
     */
    @Test
    void sixthCallPasses_seventhIs429_perTokenAndProject() {
        var limiter = new DevelopmentContextLimiter(6, Duration.ofMinutes(1));
        for (int i = 0; i < 6; i++) {
            limiter.acquire(1L, 10L);
        }

        assertThatThrownBy(() -> limiter.acquire(1L, 10L)).isInstanceOfSatisfying(ApiException.class, e -> {
            org.assertj.core.api.Assertions.assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("rate_limited");
        });
        assertThatCode(() -> limiter.acquire(2L, 10L)).doesNotThrowAnyException();
        assertThatCode(() -> limiter.acquire(1L, 11L)).doesNotThrowAnyException();
    }
}
