package com.beez.backend.meeting.dto;

import java.time.Instant;
import java.util.List;

/** 명세 00-common Meeting. MTG-A02·A03·A04 가 같은 모양을 쓴다. ID 는 문자열 */
public record MeetingResponse(
        String id,
        String projectId,
        String title,
        String changeProposalId,
        String origin,
        List<String> taskIds,
        String conflictSummary,
        String status,
        Instant closedAt,
        String closedBy,
        List<ParticipantResponse> participants,
        Instant createdAt
) {
}
