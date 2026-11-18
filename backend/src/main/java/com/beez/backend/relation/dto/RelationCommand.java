package com.beez.backend.relation.dto;

/**
 * 관계 한 쌍의 변경. from 항목이 to 항목에 의존한다(DEPENDS_ON). 방향이 다르면 다른 관계다.
 *
 * @param action     변경 종류
 * @param fromItemId 의존하는 설계 항목
 * @param toItemId   의존 대상 설계 항목
 * @param reason     근거 (필수, 2,000자 이하). 이력에 그대로 남는다
 */
public record RelationCommand(RelationAction action, Long fromItemId, Long toItemId, String reason) {
}
