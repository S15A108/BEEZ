package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateDesignItemRequest(
        @NotBlank(message = "항목 종류를 입력해 주세요.") String kind,
        @NotNull(message = "본문 스키마 버전을 입력해 주세요.") @Positive(message = "본문 스키마 버전은 양수여야 합니다.") Integer schemaVersion,
        @NotNull(message = "항목 본문을 입력해 주세요.") Map<String, Object> content,
        @Pattern(regexp = "[1-9][0-9]*", message = "출처 문서 버전 ID는 양의 정수 문자열이어야 합니다.") String sourceDocumentVersionId,
        @Size(max = 2000, message = "출처 위치는 2,000자 이하여야 합니다.") String sourceLocation
) {
}
