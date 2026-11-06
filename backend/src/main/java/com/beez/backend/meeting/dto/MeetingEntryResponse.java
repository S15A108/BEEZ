package com.beez.backend.meeting.dto;

import java.time.Instant;
import java.util.List;

/**
 * 명세 00-common MeetingEntry. text 는 DB 의 body.
 * throughEntryId·generation 은 kind=SUMMARY 일 때만 채운다(정리에 쓴 마지막 기록과 AI 생성 근거).
 */
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
        String throughEntryId,
        MeetingGeneration generation,
        Instant createdAt
) {
}
