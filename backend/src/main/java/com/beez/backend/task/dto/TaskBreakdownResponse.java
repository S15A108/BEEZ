package com.beez.backend.task.dto;

import java.util.List;

public record TaskBreakdownResponse(String baselineId, List<GeneratedTask> tasks) {
    public record GeneratedTask(String id, String key, String title, String assigneeMemberId) { }
}
