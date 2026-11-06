package com.beez.backend.planning.port;

import java.util.Map;

/**
 * 답변 반영 설계 초안 생성 경계 (134, {@code POST /v1/design-drafts}). 요청·응답은 계약 모양 그대로 Map 이다.
 * 구현은 실패를 {@link AiFailure}로 바꿔 던진다.
 */
public interface DesignDraftGateway {
    Map<String, Object> draft(Map<String, Object> input);
}
