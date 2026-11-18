package com.beez.backend.context.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.concurrent.LogThrottle;
import com.beez.backend.common.concurrent.SlidingWindowCounter;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MCP-01 get_development_context 호출 빈도 한도 (S15P31A108-399). 호출마다 유료 임베딩(질의 2회·설계·근거 후보)과
 * 요청 스레드를 최대 1분 가까이 쓰므로, 에이전트가 실패에 반복 호출해도 비용과 스레드가 한없이 늘지 않게 막는다.
 * (에이전트 토큰, 프로젝트)마다 창 안의 호출 수를 센다. 에이전트는 작업을 시작할 때 한두 번 부르는 것이 정상이라 기본 1분 6회.
 * 메모리에만 센다 (재시작하면 처음부터, 서버 1대 기준 — PresenceReportLimiter 와 같은 방식).
 */
@Component
public class DevelopmentContextLimiter {

    private static final Logger log = LoggerFactory.getLogger(DevelopmentContextLimiter.class);

    private final SlidingWindowCounter counter;
    private final LogThrottle warnThrottle = new LogThrottle(Duration.ofMinutes(10));

    public DevelopmentContextLimiter(@Value("${beez.development-context.rate-limit:6}") int limit,
                                     @Value("${beez.development-context.rate-limit-window:1m}") Duration window) {
        if (limit < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("beez.development-context.rate-limit must be >= 1 and window positive");
        }
        this.counter = new SlidingWindowCounter(limit, window);
    }

    /** 한 번 더 부를 수 있으면 센다. 넘으면 429 rate_limited. 요청 문장은 기록하지 않는다 */
    public void acquire(long agentTokenId, long projectId) {
        Instant now = Instant.now();
        if (counter.tryAcquire(agentTokenId + ":" + projectId, 1, now)) {
            return;
        }
        long skipped = warnThrottle.tryPass(now);
        if (skipped >= 0) {
            log.warn("MCP-01 호출 한도 도달: agent_token_id={} project_id={} limit={} window={} suppressed_since_last={}",
                    agentTokenId, projectId, counter.limit(), counter.window(), skipped);
        }
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "개발 컨텍스트를 너무 자주 요청했습니다. 잠시 뒤에 다시 시도하세요");
    }

    @Scheduled(fixedDelayString = "${beez.development-context.limit-purge-millis:600000}")
    public void purge() {
        counter.purge(Instant.now());
    }
}
