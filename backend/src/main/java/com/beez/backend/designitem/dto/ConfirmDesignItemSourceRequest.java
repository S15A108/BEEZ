package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * PLN-A11 기준 설계 항목의 출처 위치 확정(S15P31A108-422). 줄 범위(start_line·end_line)와 인용 없음(no_quote=true) 중 하나만 보낸다.
 * expected_document_version_id 는 화면이 읽은 출처 문서 버전이다 — 그 사이 문서가 새로 발행됐으면 옛 원문에서 고른 줄을 새 원문에
 * 저장하지 않도록 거절한다.
 */
public record ConfirmDesignItemSourceRequest(
        @NotBlank(message = "프로젝트 ID를 입력해 주세요.") String projectId,
        @NotBlank(message = "조회한 항목 버전 ID를 입력해 주세요.") String expectedVersionId,
        @NotBlank(message = "조회한 출처 문서 버전 ID를 입력해 주세요.") String expectedDocumentVersionId,
        Integer startLine,
        Integer endLine,
        Boolean noQuote
) {
}
