package com.beez.backend.validation.dto;

/** 명세 00-common RelatedTaskRef. 검증이 대조한 다른 작업의 계획 리비전 */
public record RelatedTaskRefResponse(String taskId, String planRevisionId) {
}
