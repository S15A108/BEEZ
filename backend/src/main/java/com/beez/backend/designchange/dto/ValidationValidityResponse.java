package com.beez.backend.designchange.dto;

/**
 * 명세 00-common ValidationValidity. 작업의 현재 계획에 대한 가장 최근 검증과 그 상태.
 * reason 은 STALE 일 때 validation.stale_reason, 그 밖에는 생략.
 */
public record ValidationValidityResponse(String validationId, String status, String reason) {
}
