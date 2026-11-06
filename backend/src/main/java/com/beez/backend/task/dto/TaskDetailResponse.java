package com.beez.backend.task.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import com.beez.backend.validation.dto.ValidationResponse;
import java.time.Instant;

/**
 * 명세 00-common TaskDetail. ID 는 십진 문자열, 값이 없는 선택 필드는 응답에서 빠진다(전역 non_null).
 * delta_validation_id 는 WAITING(REVALIDATION) 중 지금 기다리는 DELTA 검증 번호 — 현재 계획의 가장 최근 DELTA 가 STALE 이 아닐 때 (명세 TaskDetail).
 * validation(현재 계획의 가장 최근 검증 전체 객체)은 상세 조회(TSK-A03)에만 싣는다 — 화면이 오류 뒤 재시도 버튼을 이 값으로 그린다 (-161).
 * 목록·상태 보고·계획 변경 응답에는 넣지 않는다 (검증마다 참조·발견 항목을 더 읽어야 해서).
 * completion_review(완료 보고와 계획의 규칙 대조, -269)는 상세 조회(TSK-A03)와 COMPLETED 보고 응답(TSK-A07·MCP-12)에만 싣는다.
 * 완료됐거나 delta 완료 보고의 재검증을 기다리는 작업에만 있다.
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
        ValidationResponse validation,
        String startedValidationId,
        String deltaValidationId,
        CompletionReviewResponse completionReview,
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
                validationStatus, null, Ids.str(t.getStartedValidationId()), Ids.str(deltaValidationId), null, t.getCreatedAt(),
                t.getUpdatedAt());
    }

    /** 같은 내용에 현재 계획의 검증 결과를 붙인 것 (상세 조회용) */
    public TaskDetailResponse withValidation(ValidationResponse latest) {
        return new TaskDetailResponse(id, key, projectId, assigneeMemberId, status, waitingReason, lockVersion,
                currentPlanRevision, validationStatus, latest, startedValidationId, deltaValidationId, completionReview,
                createdAt, updatedAt);
    }

    /** 같은 내용에 완료 검증 결과를 붙인 것 (상세 조회·COMPLETED 보고 응답용) */
    public TaskDetailResponse withCompletionReview(CompletionReviewResponse review) {
        return new TaskDetailResponse(id, key, projectId, assigneeMemberId, status, waitingReason, lockVersion,
                currentPlanRevision, validationStatus, validation, startedValidationId, deltaValidationId, review,
                createdAt, updatedAt);
    }
}
