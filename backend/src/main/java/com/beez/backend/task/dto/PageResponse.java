package com.beez.backend.task.dto;

import java.util.List;

/** 커서 페이지 응답 (명세 공통). next_cursor 는 다음 페이지가 있을 때만 */
public record PageResponse<T>(List<T> items, boolean hasMore, String nextCursor) {

    public static <T> PageResponse<T> of(List<T> items, boolean hasMore, String nextCursor) {
        return new PageResponse<>(items, hasMore, nextCursor);
    }
}
