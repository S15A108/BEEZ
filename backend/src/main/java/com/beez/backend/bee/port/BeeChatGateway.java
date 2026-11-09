package com.beez.backend.bee.port;

import java.util.Map;

/**
 * 비비 질문 답변 생성 경계 (135, {@code POST /v1/bee/chat}). 요청·응답은 계약 모양 그대로 Map 이다.
 * 실패는 {@link com.beez.backend.ai.AiCallException} 으로 던진다(504 ai_timeout, 502 ai_unavailable).
 * 응답은 믿지 않는다 — 부르는 쪽이 보낸 근거 집합과 대조한 뒤에만 쓴다.
 */
public interface BeeChatGateway {
    Map<String, Object> chat(Map<String, Object> input);
}
