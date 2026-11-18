package com.beez.backend.task.dto;

import com.beez.backend.validation.dto.ValidationResponse;

public record TaskPlanProposalApplyResponse(TaskDetailResponse task, ValidationResponse validation) {}
