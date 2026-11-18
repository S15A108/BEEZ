package com.beez.backend.document.dto;

import jakarta.validation.constraints.Positive;

public record DocumentReviewRequest(@Positive(message = "조회한 작성 리비전은 양수여야 합니다.") long expectedRevision) { }
