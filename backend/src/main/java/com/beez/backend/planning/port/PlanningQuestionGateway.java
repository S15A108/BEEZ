package com.beez.backend.planning.port;

import java.util.Map;

/**
 * 요구사항 누락 후보·보완 질문 생성 경계 (133, {@code POST /v1/planning-questions}). 요청·응답은 계약 모양 그대로 Map 이다.
 * 구현은 실패를 {@link AiFailure}(502 ai_unavailable / 504 ai_timeout)로 바꿔 던진다.
 */
public interface PlanningQuestionGateway {
    Map<String, Object> generate(Map<String, Object> input);
}
