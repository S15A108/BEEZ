package com.beez.backend.common.concurrent;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 같은 경고를 간격마다 한 번만 찍게 한다 (S15P31A108-273). 공격 중에 요청마다 WARN 이 찍혀 로그가 불어나지 않게 한다.
 * <pre>
 * long skipped = throttle.tryPass(now);
 * if (skipped >= 0) log.warn("... skipped={}", skipped);
 * </pre>
 * 표는 프로세스 안에만 있다 (인스턴스마다 따로 센다).
 */
public final class LogThrottle {

    private final Duration interval;
    private final AtomicReference<Instant> nextAllowed = new AtomicReference<>(Instant.MIN);
    private final AtomicLong suppressed = new AtomicLong();

    public LogThrottle(Duration interval) {
        this.interval = interval;
    }

    /** 지금 찍어도 되면 지난번 이후 건너뛴 횟수(0 이상)를, 아니면 -1 을 돌려준다. */
    public long tryPass(Instant now) {
        Instant next = nextAllowed.get();
        if (now.isBefore(next) || !nextAllowed.compareAndSet(next, now.plus(interval))) {
            suppressed.incrementAndGet();
            return -1;
        }
        return suppressed.getAndSet(0);
    }
}
