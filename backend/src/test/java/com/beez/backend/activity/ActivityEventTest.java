package com.beez.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 활동 이벤트 형식·보조 참조 (계약 2.2·4·5·6절). 빠른 층. */
class ActivityEventTest {

    @Test
    void primaryTarget_fillsMatchingSecondaryRef_andResultDefaultsToSuccess() {
        // 보조 참조를 빠뜨리면 그 활동이 작업·제안별 필터에서 조용히 빠진다(계약: "주 대상과 같아도 넣는다")
        ActivityEvent started = ActivityEvent.of("task:501:lock:7:START", 1L, "TASK_STARTED", ActivityTargetType.TASK, 501L);
        assertThat(started.taskId()).isEqualTo(501L);
        assertThat(started.changeProposalId()).isNull();
        assertThat(started.result()).isEqualTo(ActivityResult.SUCCESS);

        assertThat(ActivityEvent.of("change-proposal:71:published", 1L, "CHANGE_PROPOSAL_PUBLISHED",
                ActivityTargetType.CHANGE_PROPOSAL, 71L).changeProposalId()).isEqualTo(71L);
        assertThat(ActivityEvent.of("baseline:9:created", 1L, "BASELINE_CREATED",
                ActivityTargetType.BASELINE, 9L).baselineId()).isEqualTo(9L);

        // 주 대상이 아닌 보조 참조는 호출측이 넣은 그대로
        ActivityEvent finished = ActivityEvent.of("validation:88:finished:2", 1L, "VALIDATION_FINISHED",
                ActivityTargetType.VALIDATION, 88L).withTaskId(501L).withResult(ActivityResult.FAILURE);
        assertThat(finished.taskId()).isEqualTo(501L);
        assertThat(finished.result()).isEqualTo(ActivityResult.FAILURE);
    }

    @Test
    void secondaryRefDifferentFromPrimaryTarget_isRejected() {
        ActivityEvent started = ActivityEvent.of("task:501:lock:7:START", 1L, "TASK_STARTED", ActivityTargetType.TASK, 501L);

        assertThatThrownBy(() -> started.withTaskId(502L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("taskId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Task:1:START", "task", "task:", "task:1:한글", "task:1 x"})
    void malformedKey_isRejected(String key) {
        assertThatThrownBy(() -> ActivityEvent.of(key, 1L, "TASK_STARTED", ActivityTargetType.TASK, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyOver200Chars_isRejected() {
        String max = "task:" + "1".repeat(195);   // 200자
        assertThat(ActivityEvent.of(max, 1L, "TASK_STARTED", ActivityTargetType.PROJECT, 1L).eventKey()).hasSize(200);
        assertThatThrownBy(() -> ActivityEvent.of(max + "1", 1L, "TASK_STARTED", ActivityTargetType.PROJECT, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contract format");
    }

    @ParameterizedTest
    @ValueSource(strings = {"task_started", "TA", "1TASK", "TASK-STARTED"})
    void malformedType_isRejected(String type) {
        assertThatThrownBy(() -> ActivityEvent.of("task:1:lock:1:START", 1L, type, ActivityTargetType.TASK, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingRequiredField_isNullPointerException() {
        // 클래스 설명에 적은 예외 종류와 실제가 같아야 호출측이 테스트에서 맞게 잡는다 (#99 리뷰 메모)
        assertThatThrownBy(() -> ActivityEvent.of("task:1:lock:1:START", 1L, "TASK_STARTED", ActivityTargetType.TASK, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("targetId");
    }
}
