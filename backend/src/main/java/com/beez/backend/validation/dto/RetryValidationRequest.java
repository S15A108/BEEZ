package com.beez.backend.validation.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** VAL-A04 요청 본문. expected_attempt 는 조회한 현재 시도 번호이자 이 재시도 요청의 식별값이다 */
public record RetryValidationRequest(@NotNull @Min(1) Integer expectedAttempt) {
}
