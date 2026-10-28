package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record DocumentSubmissionRequest(@Positive long expectedRevision,
        @NotBlank String reviewId) { }
