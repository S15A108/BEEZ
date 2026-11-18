package com.beez.backend.planning.dto;

import java.util.Map;

/**
 * PLN-A04 요청. {@code answer} 는 문장 답변(명세), {@code answer_payload} 는 선택형 같은 구조 답변(133 계약 §6)이다.
 * 둘 중 하나를 보내며 구조 답변이 있으면 그것이 우선한다. {@code status=DISMISSED} 는 질문을 답변 없이 닫는다.
 */
public record AnswerRequest(String answer, Map<String, Object> answerPayload, String status) { }
