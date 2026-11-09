package com.beez.backend.common.concurrent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 여러 개를 한꺼번에 세는 한도 (초대 메일 한도, -334). 한 번에 하나씩 세는 경우는 ClientIpRateLimiterTest 가 본다. */
class SlidingWindowCounterTest {

    private final SlidingWindowCounter counter = new SlidingWindowCounter(30, Duration.ofHours(1));
    private final Instant t0 = Instant.parse("2026-11-09T00:00:00Z");

    /**
     * 깨지면: 남은 몫보다 많은 초대 메일을 요청했을 때 일부만 세어(그런데 요청은 거부되어) 한도가 새거나,
     * 발송 실패로 되돌린 몫이 돌아오지 않아 팀장이 보내지도 못한 메일 때문에 막힌다.
     */
    @Test
    void 남은_몫보다_많으면_하나도_세지_않고_되돌린_만큼_다시_쓸_수_있다() {
        assertThat(counter.tryAcquire("1", 20, t0)).isTrue();
        assertThat(counter.tryAcquire("1", 11, t0.plusSeconds(1))).isFalse();
        assertThat(counter.used("1", t0.plusSeconds(1))).as("거부한 11통은 세지 않음").isEqualTo(20);
        assertThat(counter.tryAcquire("1", 10, t0.plusSeconds(2))).isTrue();

        counter.release("1", t0, 5);
        assertThat(counter.used("1", t0.plusSeconds(3))).isEqualTo(25);
        assertThat(counter.tryAcquire("1", 5, t0.plusSeconds(3))).isTrue();
        assertThat(counter.tryAcquire("1", 1, t0.plusSeconds(3))).isFalse();

        assertThat(counter.tryAcquire("2", 30, t0.plusSeconds(3))).as("다른 키는 따로 센다").isTrue();
    }
}
