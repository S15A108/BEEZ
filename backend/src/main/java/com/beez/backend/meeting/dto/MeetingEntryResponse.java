package com.beez.backend.meeting.dto;

import java.time.Instant;
import java.util.List;

/** 명세 00-common MeetingEntry. text 는 DB 의 body */
public record MeetingEntryResponse(
        String id,
        String meetingId,
        String kind,
        String text,
        String authorKind,
        String authorAccountId,
        String authorMemberId,
        String authorName,
        List<MeetingRef> refs,
        Instant createdAt
) {
}
