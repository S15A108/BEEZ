package com.beez.backend.designchange.dto;

/**
 * 발행 후 영향 분석이 찾은 영향 후보 하나 (S15P31A108-221). 승인된 설계가 아니다 — 확인할 거리일 뿐이고 작업 상태를 바꾸지 않는다.
 *
 * @param origin       RULE(확정 관계·계획 참조로 계산한 규칙 후보) | AI(모델이 근거와 함께 낸 후보)
 * @param taskId       영향받을 수 있는 작업
 * @param designItemId 그 작업이 닿는 바뀐 설계 항목
 * @param memberId     작업 담당 멤버
 * @param validationId 영향받을 수 있는 검증
 * @param reason       AI 후보의 사유. 규칙 후보에는 없다
 */
public record ImpactCandidateResponse(
        String origin,
        String taskId,
        String designItemId,
        String memberId,
        String validationId,
        String reason
) {
}
