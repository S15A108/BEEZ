package com.beez.backend.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** MCP-11 validate_task_plan 도구 인수 (02-mcp-tools). 어댑터(-179)가 그대로 넘긴다 */
public record ValidateTaskPlanArgs(@NotBlank String taskId, @NotBlank String planRevisionId,
                                   @NotNull Integer expectedLockVersion, String idempotencyKey) {
}
