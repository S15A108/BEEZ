package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record WorkingCopyUpdateRequest(@Positive(message = "조회한 작성 리비전은 양수여야 합니다.") long expectedRevision,
        @NotBlank(message = "문서 내용을 입력해 주세요.") String content,
        @NotBlank(message = "파일명을 입력해 주세요.") String fileName) { }
