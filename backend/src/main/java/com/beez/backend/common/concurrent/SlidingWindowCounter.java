package com.beez.backend.common.concurrent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 키(IP·계정 등)마다 최근 창 안에서 쓴 횟수를 세는 메모리 한도 (S15P31A108-273 에서 꺼냄, -334).
 * 가입 인증·비밀번호 찾기의 IP 한도와 초대 메일의 계정 한도가 같이 쓴다.
 *
 * <ul>
 *   <li>키마다 최근 사용 시각을 한도 개수까지만 든다. 거부한 요청은 세지 않는다.</li>
 *   <li>{@link #tryAcquire(String, int, Instant)}는 n 개를 한꺼번에 얻거나 하나도 얻지 않는다 — 초대 20통 중 일부만 세는 일이 없다.</li>
 *   <li>메모리에만 있다. 재시작하면 처음부터 세고 인스턴스마다 따로 센다 (서버 1대 기준, Q04·Q05).
 *       창이 지난 키는 {@link #purge}로 지운다 — 키를 바꿔 가며 보내도 표가 한 창 분량보다 커지지 않게.</li>
 * </ul>
 */
public final class SlidingWindowCounter {

    private final int limit;
    private final Duration window;
    private final ConcurrentHashMap<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public SlidingWindowCounter(int limit, Duration window) {
        this.limit = limit;
        this.window = window;
    }

    public int limit() {
        return limit;
    }

    public Duration window() {
        return window;
    }

    /** n 개가 창 안에 들어가면 now 로 n 번 세고 true, 아니면 아무것도 세지 않고 false. */
    public boolean tryAcquire(String key, int n, Instant now) {
        if (n <= 0) {
            return true;
        }
        boolean[] acquired = {false};
        hits.compute(key, (k, times) -> {
            Deque<Instant> q = times == null ? new ArrayDeque<>() : times;
            dropExpired(q, now);
            if (q.size() + n <= limit) {
                for (int i = 0; i < n; i++) {
                    q.addLast(now);
                }
                acquired[0] = true;
            }
            return q.isEmpty() ? null : q;
        });
        return acquired[0];
    }

    /** {@link #tryAcquire}로 acquiredAt 에 센 n 번을 되돌린다. 서버 사정(발송 실패·되돌린 트랜잭션)으로 처리하지 못한 요청용. */
    public void release(String key, Instant acquiredAt, int n) {
        hits.computeIfPresent(key, (k, times) -> {
            for (int i = 0; i < n && times.removeLastOccurrence(acquiredAt); i++) {
                // 같은 시각에 센 것을 n 개까지 뺀다
            }
            return times.isEmpty() ? null : times;
        });
    }

    /** 창 안에서 이 키가 쓴 횟수. */
    public int used(String key, Instant now) {
        Deque<Instant> q = hits.computeIfPresent(key, (k, times) -> {
            dropExpired(times, now);
            return times.isEmpty() ? null : times;
        });
        return q == null ? 0 : q.size();
    }

    /** 창이 지난 키를 표에서 뺀다. */
    public void purge(Instant now) {
        for (String key : hits.keySet()) {
            hits.computeIfPresent(key, (k, times) -> {
                dropExpired(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    private void dropExpired(Deque<Instant> times, Instant now) {
        Instant since = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(since)) {
            times.pollFirst();
        }
    }
}
