package com.beez.backend.task.dto;

import com.beez.backend.task.dto.TaskBreakdownResponse.GeneratedTask;
import java.util.List;

public record TaskBreakdownJobResponse(String id, String baselineId, Status status, List<GeneratedTask> tasks,
                                       String errorCode, Boolean retryable) {
    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }
}
