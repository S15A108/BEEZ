package com.beez.backend.common.api;

import java.util.List;
import java.util.function.Function;

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

    /**
     * id 내림차순 커서의 시작점. 없거나 빈 cursor 는 첫 페이지(Long.MAX_VALUE), 숫자가 아니면 400.
     * 쿼리는 {@code id < beforeId} 를 걸고 limit+1 개를 읽어 {@link #fromRows} 에 넘긴다.
     */
    public static long beforeId(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Long.MAX_VALUE;
        }
        return Ids.parse(cursor, "cursor");
    }

    /**
     * limit+1 개를 읽은 rows 를 한 페이지로 자른다. 다 차면 다음 페이지가 있고, 커서는 페이지 마지막 행의 id 다.
     * toItems 는 잘린 페이지만 받는다 — 기준 설계 요약처럼 페이지 단위로 한 번에 읽을 것은 거기서 읽는다.
     */
    public static <R, T> PageResponse<T> fromRows(List<R> rows, int size, Function<R, Long> idOf,
                                                  Function<List<R>, List<T>> toItems) {
        boolean hasMore = rows.size() > size;
        List<R> page = rows;
        if (hasMore) {
            page = rows.subList(0, size);
        }
        String nextCursor = null;
        if (hasMore) {
            nextCursor = Ids.str(idOf.apply(page.get(page.size() - 1)));
        }
        return new PageResponse<>(toItems.apply(page), hasMore, nextCursor);
    }

    public static <T> PageResponse<T> of(List<T> items, boolean hasMore, String nextCursor) {
        return new PageResponse<>(items, hasMore, nextCursor);
    }
}
