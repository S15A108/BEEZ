package com.beez.backend.task.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Only an explicit apply request creates a plan revision. */
public record TaskPlanProposalResponse(String id, String baselineId, String sourceValidationId,
        int expectedRevisionNo, int expectedLockVersion, TaskPlanInput beforePlan, TaskPlanInput proposedPlan,
        List<Map<String, Object>> changes, List<Map<String, Object>> evidence, Instant expiresAt) {}
