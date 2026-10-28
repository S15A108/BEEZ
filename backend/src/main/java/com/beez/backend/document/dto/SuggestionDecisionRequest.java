package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record SuggestionDecisionRequest(@Positive long expectedRevision,
        @NotBlank
        @Pattern(regexp = "APPLY|SKIP") String decision) { }
