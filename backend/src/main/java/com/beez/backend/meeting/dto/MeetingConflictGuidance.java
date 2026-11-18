package com.beez.backend.meeting.dto;

import java.util.List;

/** Evidence captured when a blocking work conflict opens a meeting. */
public record MeetingConflictGuidance(String validationId, List<Task> tasks, List<Conflict> conflicts) {
    public record Task(String taskId, String title, String planRevisionId, Integer revisionNo) {}

    public record Conflict(String message, String nextAction, DesignItem designItem, List<Difference> differences) {}

    public record DesignItem(String id, String versionId, String key, String title, String documentId,
                             String documentVersionId, String location) {}

    public record Difference(String path, String firstValue, String secondValue) {}
}
