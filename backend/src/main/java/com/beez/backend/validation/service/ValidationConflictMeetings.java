package com.beez.backend.validation.service;

import com.beez.backend.validation.event.ConflictFindingsSaved;
import com.beez.backend.validation.repository.FindingRow;
import com.beez.backend.validation.repository.ValidationRow;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;

final class ValidationConflictMeetings {

    private ValidationConflictMeetings() {}

    /** The meeting listener runs after the transaction that saved these findings commits. */
    static void publish(ApplicationEventPublisher publisher, ValidationRow validation, List<FindingRow> findings) {
        List<Long> relatedTaskIds = findings.stream()
                .filter(finding -> "CONFLICT".equals(finding.kind()) && "BLOCKING".equals(finding.severity()))
                .map(FindingRow::relatedTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (!relatedTaskIds.isEmpty()) {
            publisher.publishEvent(new ConflictFindingsSaved(validation.projectId(), validation.id(), validation.taskId(),
                    relatedTaskIds));
        }
    }
}
