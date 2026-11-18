package com.beez.backend.auth.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** IP 한도의 창 계산 (Q04: IP당 1시간 10회, -273). 시각을 넣어 DB·HTTP 없이 본다. */
class ClientIpRateLimiterTest {

    private final ClientIpRateLimiter limiter =
            new ClientIpRateLimiter(new AuthGuardProperties(10, Duration.ofHours(1), Duration.ofDays(7)));
    private final Instant t0 = Instant.parse("2026-11-09T00:00:00Z");

    /**
     * 깨지면: 한 IP 가 한 시간에 10번을 넘게 보내거나, 한도로 거부한 요청까지 세어 같은 공유기 뒤 사용자가 창이 지나도 풀리지 않거나,
     * 한 시간이 지나도 계속 막힌다.
     */
    @Test
    void 한_시간에_열_번_넘으면_거부하고_거부한_요청은_세지_않고_창이_지나면_다시_허용() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.tryAcquire("203.0.113.1", t0.plusSeconds(i))).as("요청 " + (i + 1)).isTrue();
        }
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("203.0.113.1", t0.plusSeconds(60 + i))).isFalse();
        }
        assertThat(limiter.tryAcquire("203.0.113.2", t0.plusSeconds(60))).as("다른 IP 는 따로 센다").isTrue();

        // 첫 요청이 창 밖으로 나가는 순간 한 자리가 빈다. 거부한 5번이 세어졌다면 여기서도 막힌다
        assertThat(limiter.tryAcquire("203.0.113.1", t0.plus(Duration.ofHours(1)))).isTrue();
        assertThat(limiter.tryAcquire("203.0.113.1", t0.plus(Duration.ofHours(1)))).as("빈 자리는 하나뿐").isFalse();
    }
}
