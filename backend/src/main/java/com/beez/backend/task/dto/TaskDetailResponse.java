package com.beez.backend.task.dto;

import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import java.time.Instant;

/**
 * 명세 00-common TaskDetail.
 * validation(전체 객체)은 검증 실행(S15P31A108-59)에서 붙인다. 지금은 상태 문자열(validation_status)만 계산해 넣는다.
 */
public record TaskDetailResponse(
        Long id,
        String key,
        Long projectId,
        Long assigneeMemberId,
        TaskStatus status,
        WaitingReason waitingReason,
        Integer lockVersion,
        TaskPlanRevisionResponse currentPlanRevision,
        String validationStatus,
        Long startedValidationId,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskDetailResponse of(Task t, TaskPlanRevisionResponse current, String validationStatus) {
        return new TaskDetailResponse(t.getId(), t.getKey(), t.getProjectId(), t.getAssigneeMemberId(),
                t.getStatus(), t.getWaitingReason(), t.getLockVersion(), current, validationStatus,
                t.getStartedValidationId(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
