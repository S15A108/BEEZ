package com.beez.backend.validation.service;

import java.util.Map;

/**
 * 의미 분석 호출 (AI-A02 {@code POST /v1/validate}). 워커가 DB 트랜잭션 밖에서 부른다.
 * 실제 구현은 {@link AiValidationAnalyzer}, 통합 테스트는 이 인터페이스를 가짜로 바꿔 끼운다 (AI 서버에 아직 이 경로가 없다).
 */
public interface ValidationAnalyzer {

    /**
     * @return AI 응답 본문 (result·origin·model·usage)
     * @throws AnalyzerException 시간 초과·호출 실패·HTTP 오류 — 규약의 ERROR 분류를 담는다
     */
    Map<String, Object> analyze(Map<String, Object> request) throws AnalyzerException;

    /** 분석 호출 실패. errorCode 는 공통 "검증 실행 오류 규약"의 Validation.error_code 값 */
    class AnalyzerException extends Exception {
        private final String errorCode;

        public AnalyzerException(String errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
