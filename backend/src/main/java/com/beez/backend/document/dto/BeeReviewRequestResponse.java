package com.beez.backend.document.dto;

/**
 * 작업본 조회에 싣는 최근 비비 검토 요청 상태(S15P31A108-382). 올린 사람이 아닌 팀원도 실패를 알 수 있고
 * 새로고침해도 실패 안내가 유지된다. status 는 RUNNING·FAILED·COMPLETED, errorCode 는 FAILED 일 때만 있다.
 */
public record BeeReviewRequestResponse(String status, String errorCode, String updatedAt) { }
