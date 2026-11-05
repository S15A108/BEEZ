package com.beez.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.notification.NotificationInboxService.ReadTarget;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/**
 * NTF-A02 요청 조합 규칙(명세 Request 표: ids 와 all 은 둘 중 하나, project_id 는 all=true 의 범위).
 * 깨지면 모호한 요청(둘 다·둘 다 없음)이 400 대신 무언가를 읽음 처리한다.
 */
class NotificationReadTargetTest {

    static Stream<NotificationReadRequest> ambiguousRequests() {
        return Stream.of(
                null,
                new NotificationReadRequest(null, null, null),
                new NotificationReadRequest(List.of(), null, null),
                new NotificationReadRequest(null, false, null),
                new NotificationReadRequest(List.of("1"), true, null),
                new NotificationReadRequest(List.of("1"), null, "5"),
                new NotificationReadRequest(List.of("abc"), null, null));
    }

    @ParameterizedTest
    @MethodSource("ambiguousRequests")
    void ambiguousOrMalformed_is400(NotificationReadRequest request) {
        assertThatThrownBy(() -> ReadTarget.of(request)).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("validation_failed");
    }

    /** 깨지면 같은 ID 를 두 번 보낸 정상 요청이 소유 확인 건수와 어긋나 404 가 된다. */
    @Test
    void duplicateIds_countAsOne() {
        assertThat(ReadTarget.of(new NotificationReadRequest(List.of("7", "7", "8"), null, null)).ids())
                .containsExactly(7L, 8L);
    }
}
