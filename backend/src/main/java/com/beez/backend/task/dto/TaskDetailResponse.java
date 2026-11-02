package com.beez.backend.task.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import java.time.Instant;

/**
 * 명세 00-common TaskDetail. ID 는 십진 문자열, 값이 없는 선택 필드는 응답에서 빠진다(전역 non_null).
 * delta_validation_id 는 WAITING(REVALIDATION) 중 지금 기다리는 DELTA 검증 번호 — 현재 계획의 가장 최근 DELTA 가 STALE 이 아닐 때 (명세 TaskDetail). validation(전체 객체)은 아직 없다.
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
        return of(t, current, validationStatus, null);
    }

    public static TaskDetailResponse of(Task t, TaskPlanRevisionResponse current, String validationStatus,
                                        Long deltaValidationId) {
        return new TaskDetailResponse(Ids.str(t.getId()), t.getKey(), Ids.str(t.getProjectId()),
                Ids.str(t.getAssigneeMemberId()), t.getStatus(), t.getWaitingReason(), t.getLockVersion(), current,
                validationStatus, Ids.str(t.getStartedValidationId()), Ids.str(deltaValidationId), t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
