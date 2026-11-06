package com.beez.backend.meeting.dto;

import java.time.Instant;
import java.util.List;

/** 명세 00-common MeetingSummary (MTG-A11·A12 응답). 값이 없는 선택 필드는 응답에서 빠진다 */
public record MeetingSummaryResponse(
        String id,
        String meetingId,
        MeetingEntryResponse entry,
        String throughEntryId,
        List<String> conflicts,
        List<SummaryResult.Decision> decisions,
        List<SummaryResult.DocumentChange> documentChanges,
        List<SummaryResult.ActionItem> actionItems,
        String previousSummaryId,
        String feedback,
        MeetingGeneration generation,
        Instant createdAt
) {
}
