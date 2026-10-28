package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record DocumentSubmissionRequest(@Positive(message = "조회한 작성 리비전은 양수여야 합니다.") long expectedRevision,
        @NotBlank(message = "비비 검토 ID를 입력해 주세요.") String reviewId) { }
