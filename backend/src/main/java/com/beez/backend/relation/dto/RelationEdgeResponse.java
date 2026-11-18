package com.beez.backend.relation.dto;

/**
 * 명세 00-common RelationEdge.
 *
 * @param confirmed           명시적으로 확정된 관계인지. 설계 의존의 재확인 필요·후보는 false
 * @param status              설계 의존 관계일 때만: CONFIRMED·NEEDS_RECHECK·CANDIDATE
 * @param designItemVersionId 계획·검증이 본 설계 항목 버전 (당시 버전과 연결, 해당할 때만)
 */
public record RelationEdgeResponse(String fromId, String fromType, String toId, String toType, String relation,
                                   boolean confirmed, String status, String designItemVersionId) {
}
