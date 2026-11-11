package com.beez.backend.task.service;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 최근 한 시간 안에 모델을 부르기 전에 거절된 초안 요청을 사람·프로젝트별로 센다(S15P31A108-385).
 * 이런 요청은 llm_usage 에 남지 않지만 검색어 임베딩 비용은 이미 나갔으므로 시간당 횟수 제한에 함께 센다.
 * 메모리 카운터라 서버를 다시 시작하면 비워진다. 정밀한 한도가 아니라 비용 방어용이다.
 */
final class TaskPlanDraftWindow {
    static final long WINDOW_MILLIS = 60L * 60 * 1000;
    private static final int MAX_PER_KEY = 1000;
    private static final int SWEEP_ABOVE = 1000;

    private final Map<String, ArrayDeque<Long>> entries = new HashMap<>();

    synchronized void add(String key, long nowMillis) {
        ArrayDeque<Long> times = entries.computeIfAbsent(key, k -> new ArrayDeque<>());
        prune(times, nowMillis);
        times.addLast(nowMillis);
        if (times.size() > MAX_PER_KEY) times.removeFirst();
        if (entries.size() > SWEEP_ABOVE) sweep(nowMillis);
    }

    synchronized int count(String key, long nowMillis) {
        ArrayDeque<Long> times = entries.get(key);
        if (times == null) return 0;
        prune(times, nowMillis);
        if (times.isEmpty()) {
            entries.remove(key);
            return 0;
        }
        return times.size();
    }

    private static void prune(ArrayDeque<Long> times, long nowMillis) {
        while (!times.isEmpty() && nowMillis - times.peekFirst() >= WINDOW_MILLIS) times.removeFirst();
    }

    /** 오래 안 쓴 사람·프로젝트의 빈 목록이 쌓이지 않게 비운다. */
    private void sweep(long nowMillis) {
        entries.values().forEach(times -> prune(times, nowMillis));
        entries.values().removeIf(ArrayDeque::isEmpty);
    }
}
