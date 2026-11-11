package com.beez.backend.presence;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.concurrent.LogThrottle;
import com.beez.backend.common.concurrent.SlidingWindowCounter;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 위치 보고 빈도 제한. (계정, 프로젝트)마다 창 안의 보고 수를 센다. 메모리에만 센다 (재시작하면 처음부터, 서버 1대 기준). */
@Component
public class PresenceReportLimiter {

    private static final Logger log = LoggerFactory.getLogger(PresenceReportLimiter.class);

    private final SlidingWindowCounter counter;
    private final LogThrottle warnThrottle = new LogThrottle(Duration.ofMinutes(10));

    public PresenceReportLimiter(PresenceProperties properties) {
        this.counter = new SlidingWindowCounter(properties.rateLimit(), properties.rateLimitWindow());
    }

    /** 한 번 더 보낼 수 있으면 센다. 넘으면 429 rate_limited. */
    void acquire(long accountId, long projectId) {
        Instant now = Instant.now();
        if (counter.tryAcquire(key(accountId, projectId), 1, now)) {
            return;
        }
        long skipped = warnThrottle.tryPass(now);
        if (skipped >= 0) {
            // 위치 값은 찍지 않는다
            log.warn("접속 상태 보고 한도 도달: account_id={} project_id={} limit={} window={} suppressed_since_last={}",
                    accountId, projectId, counter.limit(), counter.window(), skipped);
        }
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "접속 상태를 너무 자주 보냈습니다. 잠시 뒤에 다시 시도하세요");
    }

    @Scheduled(fixedDelayString = "${beez.presence.limit-purge-millis:600000}")
    public void purge() {
        counter.purge(Instant.now());
    }

    private static String key(long accountId, long projectId) {
        return accountId + ":" + projectId;
    }
}
