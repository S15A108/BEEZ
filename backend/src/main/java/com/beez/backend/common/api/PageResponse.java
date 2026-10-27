package com.beez.backend.common.api;

import java.util.List;

/** 커서 페이지 응답 (명세 공통). next_cursor 는 다음 페이지가 있을 때만 */
public record PageResponse<T>(List<T> items, boolean hasMore, String nextCursor) {

    /** 명세 공통 페이지 크기: 기본 20, 최대 100 */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** 없거나 1 미만이면 기본값, 최대값을 넘으면 최대값. */
    public static int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    public static <T> PageResponse<T> of(List<T> items, boolean hasMore, String nextCursor) {
        return new PageResponse<>(items, hasMore, nextCursor);
    }
}
