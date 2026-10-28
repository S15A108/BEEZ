package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record SuggestionDecisionRequest(@Positive(message = "조회한 작성 리비전은 양수여야 합니다.") long expectedRevision,
        @NotBlank(message = "보완 제안의 처리 방식을 선택해 주세요.")
        @Pattern(regexp = "APPLY|SKIP", message = "보완 제안은 반영하기 또는 넘기기를 선택해 주세요.") String decision) { }
