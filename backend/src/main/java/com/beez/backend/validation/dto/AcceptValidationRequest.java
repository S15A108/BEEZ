package com.beez.backend.validation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** VAL-A01 요청 본문 */
public record AcceptValidationRequest(@NotBlank String planRevisionId, @NotNull Integer expectedLockVersion) {
}
