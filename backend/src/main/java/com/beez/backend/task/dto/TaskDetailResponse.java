package com.beez.backend.task.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import java.time.Instant;

/**
 * 명세 00-common TaskDetail. ID 는 십진 문자열, 값이 없는 선택 필드는 응답에서 빠진다(전역 non_null).
 * validation(전체 객체)·delta_validation_id 는 검증 실행(S15P31A108-159)에서 채운다.
 */
public record TaskDetailResponse(
        String id,
        String key,
        String projectId,
        String assigneeMemberId,
        TaskStatus status,
        WaitingReason waitingReason,
        Integer lockVersion,
        TaskPlanRevisionResponse currentPlanRevision,
        String validationStatus,
        String startedValidationId,
        String deltaValidationId,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskDetailResponse of(Task t, TaskPlanRevisionResponse current, String validationStatus) {
        return new TaskDetailResponse(Ids.str(t.getId()), t.getKey(), Ids.str(t.getProjectId()),
                Ids.str(t.getAssigneeMemberId()), t.getStatus(), t.getWaitingReason(), t.getLockVersion(), current,
                validationStatus, Ids.str(t.getStartedValidationId()), null, t.getCreatedAt(), t.getUpdatedAt());
    }
}
