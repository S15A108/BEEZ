package com.beez.backend.relation.dto;

import java.util.List;

/**
 * 관계 저장 서비스 반환.
 *
 * @param relationSetRevision 처리 뒤의 관계 집합 리비전. 확정 관계 집합이 바뀌었으면 1 오른 값, 아니면 지금 값(없으면 0)
 * @param changes             바뀐 관계 (재확인 표시 → 명령 순서)
 */
public record RelationChangeResult(long relationSetRevision, List<RelationChange> changes) {
}
