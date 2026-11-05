package com.beez.backend.mcp.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** MCP-14 retry_validation 도구 인수 (02-mcp-tools). 어댑터(-179)가 그대로 넘긴다 */
public record RetryValidationArgs(@NotBlank String validationId, @NotNull @Min(1) Integer expectedAttempt, String idempotencyKey) {
}
