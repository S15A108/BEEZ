package com.beez.backend.common.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 공통 커서 페이지 경계 (명세 공통: 기본 20·최대 100, next_cursor 는 다음 페이지가 있을 때만). MON-A04·NTF-A01 이 함께 쓴다. */
class PageResponseTest {

    private static PageResponse<Long> page(List<Long> rows, int size) {
        return PageResponse.fromRows(rows, size, Function.identity(), p -> p);
    }

    /** 깨지면 정확히 limit 개 남았을 때 빈 다음 페이지를 가리키는 커서가 생기거나, limit+1 개일 때 다음 페이지를 잃는다. */
    @Test
    void exactlyFullPage_hasNoNextCursor_oneMoreRow_cutsAndPointsAtLastShown() {
        PageResponse<Long> full = page(List.of(9L, 8L), 2);
        assertThat(full.items()).containsExactly(9L, 8L);
        assertThat(full.hasMore()).isFalse();
        assertThat(full.nextCursor()).isNull();

        PageResponse<Long> more = page(List.of(9L, 8L, 7L), 2);
        assertThat(more.items()).containsExactly(9L, 8L);
        assertThat(more.hasMore()).isTrue();
        assertThat(more.nextCursor()).isEqualTo("8");
        assertThat(PageResponse.beforeId(more.nextCursor())).isEqualTo(8L);
    }

    /** 깨지면 limit 0·음수·과대 요청이 빈 페이지나 무제한 조회가 된다. */
    @Test
    void limit_defaultsAndCaps() {
        assertThat(PageResponse.normalizeLimit(null)).isEqualTo(20);
        assertThat(PageResponse.normalizeLimit(0)).isEqualTo(20);
        assertThat(PageResponse.normalizeLimit(-5)).isEqualTo(20);
        assertThat(PageResponse.normalizeLimit(100)).isEqualTo(100);
        assertThat(PageResponse.normalizeLimit(101)).isEqualTo(100);
    }

    /** 깨지면 빈 cursor 가 400 이 되거나, 숫자가 아닌 cursor 가 500 으로 샌다. */
    @Test
    void cursor_blankIsFirstPage_garbageIs400() {
        assertThat(PageResponse.beforeId(null)).isEqualTo(Long.MAX_VALUE);
        assertThat(PageResponse.beforeId("")).isEqualTo(Long.MAX_VALUE);
        assertThatThrownBy(() -> PageResponse.beforeId("abc"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus().value()).isEqualTo(400));
    }
}
