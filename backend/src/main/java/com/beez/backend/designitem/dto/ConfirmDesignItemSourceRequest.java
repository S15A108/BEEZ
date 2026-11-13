package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * PLN-A11 기준 설계 항목의 출처 위치 확정(S15P31A108-422). 줄 범위(start_line·end_line)와 인용 없음(no_quote=true) 중 하나만 보낸다.
 */
public record ConfirmDesignItemSourceRequest(
        @NotBlank(message = "프로젝트 ID를 입력해 주세요.") String projectId,
        @NotBlank(message = "조회한 항목 버전 ID를 입력해 주세요.") String expectedVersionId,
        Integer startLine,
        Integer endLine,
        Boolean noQuote
) {
}
