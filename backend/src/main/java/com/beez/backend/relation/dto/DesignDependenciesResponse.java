package com.beez.backend.relation.dto;

import java.util.List;

/**
 * TRC-A01 응답.
 *
 * @param relationSetRevision 조회 시점의 관계 집합 리비전 (관계 변경이 한 번도 없으면 0)
 * @param items               이 항목이 한쪽 끝인 확정·재확인 필요 관계
 * @param candidates          이 항목이 한쪽 끝인 미확정 후보
 */
public record DesignDependenciesResponse(long relationSetRevision, List<DesignDependencyResponse> items,
                                         List<DependencyCandidateResponse> candidates) {
}
