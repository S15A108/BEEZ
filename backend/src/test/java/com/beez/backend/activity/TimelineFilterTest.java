package com.beez.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import org.junit.jupiter.api.Test;

/** MON-A04 필터 입력 해석. */
class TimelineFilterTest {

    /** 깨지면 '전체' 화면이 보내는 빈 값이 400 이 되거나 빈 결과가 된다. */
    @Test
    void blankValues_meanNoFilter() {
        assertThat(TimelineFilter.of("", " ", "", null, ""))
                .isEqualTo(new TimelineFilter(null, null, null, null, null));
    }

    /** 깨지면 오타 난 actor_type 이 조용히 빈 목록이 되어 화면이 '활동 없음'으로 오해한다. */
    @Test
    void actorType_isCaseInsensitive_unknownIs400() {
        assertThat(TimelineFilter.of(null, "agent", null, null, null).actorType()).isEqualTo("AGENT");
        assertThatThrownBy(() -> TimelineFilter.of(null, "ROBOT", null, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        assertThatThrownBy(() -> TimelineFilter.of(null, null, "abc", null, null))
                .isInstanceOf(ApiException.class);
    }
}
