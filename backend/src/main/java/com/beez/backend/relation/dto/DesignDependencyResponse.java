package com.beez.backend.relation.dto;

import java.time.Instant;

/**
 * 명세 00-common DesignDependency. 확정 관계(CONFIRMED)와 재확인 필요(NEEDS_RECHECK)를 담는다.
 * 재확인 필요는 아직 해제되지 않았지만 규칙의 확정 근거로 쓰지 않는다 — status 로 구분한다.
 */
public record DesignDependencyResponse(String fromDesignItemId, String toDesignItemId, String relation, String origin,
                                       String status, String reason, String confirmedBy, Instant confirmedAt) {
}
