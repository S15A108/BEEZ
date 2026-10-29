package com.beez.backend.relation.dto;

/**
 * 설계 항목 의존관계 한 쌍에 하는 변경 (관계 저장 서비스 입력).
 * <ul>
 *   <li>CONFIRM — 팀장이 관계를 확정한다. 없음·해제·후보·거절 상태에서 가능</li>
 *   <li>KEEP — 재확인 필요인 관계를 팀장이 유지한다</li>
 *   <li>RELEASE — 확정·재확인 필요인 관계를 해제한다</li>
 *   <li>ADD_CANDIDATE — AI 등이 추천한 관계를 후보로 둔다. 확정 관계 집합은 바뀌지 않는다</li>
 *   <li>REJECT_CANDIDATE — 후보를 거절한다</li>
 * </ul>
 * "재확인 필요" 표시는 쌍이 아니라 바뀐 항목 단위로 하므로 {@link RelationChangeRequest#recheckItemIds()} 로 받는다.
 */
public enum RelationAction {
    CONFIRM,
    KEEP,
    RELEASE,
    ADD_CANDIDATE,
    REJECT_CANDIDATE;

    /** 후보만 다루는 변경인지. 후보 변경은 확정 관계 집합 리비전을 올리지 않는다 */
    public boolean isCandidateAction() {
        return this == ADD_CANDIDATE || this == REJECT_CANDIDATE;
    }
}
