package com.beez.backend.task.dto;

import java.util.List;

/**
 * 명세 00-common CompletionReview. 완료 보고를 계획과 규칙으로 대조한 결과 (S15P31A108-269, VAL-07 작은 범위).
 * AI 를 쓰지 않고, 완료를 막지 않는다 — 사람이 볼 "확인할 거리" 목록이다. 코드 품질을 보증하지 않는다 (AC-15 와 같은 원칙).
 *
 * @param reviewVersion            대조 규칙 판. 규칙이 바뀌면 올린다
 * @param reportId                 대조한 완료 보고
 * @param changesReported          에이전트가 changed_design_item_ids 를 보냈는가. false 면 범위 이탈은 판단하지 않았다
 * @param scopeDeviations          범위 이탈 후보 — 보고된 변경 중 계획이 바꾸기로 하지 않은 항목
 * @param unreportedPlannedChanges 계획이 바꾸기로 한(CHANGES) 항목 중 변경 보고에 없는 것. changesReported 일 때만 채운다
 * @param unmetCriteria            met=false 로 보고된 완료 기준
 * @param uncheckedCriteria        계획의 완료 기준 중 completion_checks 에 없는 것
 * @param handoverRecorded         이 작업의 공유 인수인계 기록(write_context kind=HANDOVER)이 있는가. 없으면 경고만
 * @param deviationCount           scopeDeviations 개수
 * @param notice                   화면에 함께 보일 안내 (품질 보증 아님)
 */
public record CompletionReviewResponse(
        String reviewVersion,
        String reportId,
        boolean changesReported,
        List<ScopeDeviation> scopeDeviations,
        List<String> unreportedPlannedChanges,
        List<String> unmetCriteria,
        List<String> uncheckedCriteria,
        boolean handoverRecorded,
        int deviationCount,
        String notice
) {

    /**
     * @param designItemId 보고된 변경 항목
     * @param reason       NO_CONTRACT_CHANGE_DECLARED(계획이 "계약 변경 없음"을 선언) | NOT_IN_PLAN(계획에 없는 항목) |
     *                     NOT_PLANNED_TO_CHANGE(계획에는 있지만 읽기·구현으로만 다루기로 함)
     */
    public record ScopeDeviation(String designItemId, String reason) {
    }
}
