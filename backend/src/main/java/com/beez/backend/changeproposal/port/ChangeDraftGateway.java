package com.beez.backend.changeproposal.port;

import java.util.Map;

/**
 * AI-A06 문서 수정 초안 생성 경계 (S15P31A108-233). 요청·응답은 명세 모양 그대로 Map 이다.
 * 구현은 실패를 {@link com.beez.backend.common.api.ApiException}(502 ai_unavailable / 504 ai_timeout)으로 바꿔 던진다.
 */
public interface ChangeDraftGateway {
    Map<String, Object> draft(Map<String, Object> input);
}
