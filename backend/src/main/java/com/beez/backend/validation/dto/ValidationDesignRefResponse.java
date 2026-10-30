package com.beez.backend.validation.dto;

/** 명세 00-common ValidationDesignRef. 검증이 본 설계 항목 버전 */
public record ValidationDesignRefResponse(String designItemId, String designItemVersionId, String relation) {
}
