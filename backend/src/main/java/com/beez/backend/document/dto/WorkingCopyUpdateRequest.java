package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record WorkingCopyUpdateRequest(@Positive long expectedRevision,
        @NotBlank String content,
        @NotBlank String fileName) { }
