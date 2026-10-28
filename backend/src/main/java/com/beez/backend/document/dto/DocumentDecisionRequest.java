package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DocumentDecisionRequest(@NotBlank
        @Pattern(regexp = "APPROVE|REJECT") String decision,
        @Size(max = 10000) String rejectionReason) { }
