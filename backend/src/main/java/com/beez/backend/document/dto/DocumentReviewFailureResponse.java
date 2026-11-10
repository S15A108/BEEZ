package com.beez.backend.document.dto;

/**
 * 지금 본문에 대한 마지막 비비 검토 요청이 실패했음을 알린다(S15P31A108-383). 검토를 부를 수 없는 팀원 화면이
 * 실패한 검토를 '검토 중'으로 계속 기다리지 않고, 올린 사람 화면은 창을 다시 열 때 실패한 검토를 자동으로 되풀이하지 않게 한다.
 */
public record DocumentReviewFailureResponse(String errorCode, String failedAt) { }
