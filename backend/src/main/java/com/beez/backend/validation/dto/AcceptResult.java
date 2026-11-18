package com.beez.backend.validation.dto;

/**
 * 접수 결과. 규칙만으로 끝났으면 finished=true 로 HTTP 200, 의미 분석을 큐에 넣었으면 false 로 202 (VAL-A01 응답 규칙).
 */
public record AcceptResult(ValidationResponse validation, boolean finished) {
}
