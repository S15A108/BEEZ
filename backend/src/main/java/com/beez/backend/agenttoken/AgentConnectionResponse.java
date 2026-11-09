package com.beez.backend.agenttoken;

import java.time.Instant;

/**
 * 명세 00-common AgentConnection (AGT-A05, S15P31A108-75). 연결·마지막 통신 관측만 담는다 — 작업·검증·대기는 MON-A05.
 * {@code last_error_code}(수정안 요청)는 연결 실패 원인을 남기는 곳이 아직 없어 내려가지 않는다(전역 non_null 로 빠짐).
 *
 * @param lastReportAt 마지막 통신 시각(상태 보고가 아님, 1분 단위). {@link AgentConnectionRule}
 */
public record AgentConnectionResponse(
        String id,
        String memberId,
        boolean connected,
        Instant lastReportAt,
        boolean statusCheckRequired
) {
}
