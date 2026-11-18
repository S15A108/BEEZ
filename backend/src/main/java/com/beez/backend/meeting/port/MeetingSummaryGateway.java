package com.beez.backend.meeting.port;

import java.util.Map;

/**
 * AI-A08 회의 정리(SUMMARY) 경계 (S15P31A108-235). 요청·응답은 명세 모양 그대로 Map 이다.
 * 구현은 실패를 {@link com.beez.backend.common.api.ApiException}(502 ai_unavailable / 504 ai_timeout)으로 바꿔 던진다.
 */
public interface MeetingSummaryGateway {
    Map<String, Object> summarize(Map<String, Object> input);
}
