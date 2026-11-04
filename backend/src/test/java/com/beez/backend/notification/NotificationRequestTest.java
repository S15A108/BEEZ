package com.beez.backend.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 알림 요청 형식·자르기 (계약 2.2·5·6절). 빠른 층. */
class NotificationRequestTest {

    private static NotificationRequest request(String key, String type, NotificationTargetType target, Long documentId) {
        return new NotificationRequest(key, 1L, 2L, type, "제목", "본문", target, 3L, documentId);
    }

    @Test
    void truncate_countsCodePoints_soEmojiIsNotSplit() {
        // "😀" 는 UTF-16 두 칸. 칸 단위로 자르면 반쪽 서로게이트가 남아 화면에서 깨진다
        String title = "😀".repeat(NotificationRequester.TITLE_MAX + 1);

        String cut = NotificationRequester.truncate(title, NotificationRequester.TITLE_MAX);

        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(NotificationRequester.TITLE_MAX);
        assertThat(cut).isEqualTo("😀".repeat(NotificationRequester.TITLE_MAX - 1) + "…");
        String exact = "😀".repeat(NotificationRequester.TITLE_MAX);
        assertThat(NotificationRequester.truncate(exact, NotificationRequester.TITLE_MAX)).isSameAs(exact);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Task:1:x:to:2", "task", "task:", "task:1:한글", "task:1 x"})
    void malformedKey_isRejected(String key) {
        assertThatThrownBy(() -> request(key, "TASK_ASSIGNED", NotificationTargetType.TASK, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyOver200Chars_isRejected() {
        String max = "task:" + "1".repeat(190) + ":to:2";   // 200자
        assertThat(request(max, "TASK_ASSIGNED", NotificationTargetType.TASK, null).notificationKey()).hasSize(200);
        assertThatThrownBy(() -> request("task:1" + max.substring(5), "TASK_ASSIGNED", NotificationTargetType.TASK, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract format");
    }

    @ParameterizedTest
    @ValueSource(strings = {"baseline:9:created", "task:1:assigned:to:3", "task:1:assigned:to:12"})
    void keyWithoutMatchingRecipientSuffix_isRejected(String key) {
        // 수신자 2에게 보내는 요청. 수신자 조각이 없거나 다르면 여러 수신자가 한 키를 나눠 써 첫 사람 말고는 사라진다
        assertThatThrownBy(() -> request(key, "TASK_ASSIGNED", NotificationTargetType.TASK, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(":to:2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"task_assigned", "TA", "1TASK", "TASK-ASSIGNED"})
    void malformedType_isRejected(String type) {
        assertThatThrownBy(() -> request("task:1:assigned:to:2", type, NotificationTargetType.TASK, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void documentVersionTarget_requiresDocumentId() {
        assertThatThrownBy(() -> request("doc-submission:33:approved:to:2", "DOCUMENT_APPROVED",
                NotificationTargetType.DOCUMENT_VERSION, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documentId");
        assertThat(request("doc-submission:33:approved:to:2", "DOCUMENT_APPROVED",
                NotificationTargetType.DOCUMENT_VERSION, 7L).documentId()).isEqualTo(7L);
    }
}
