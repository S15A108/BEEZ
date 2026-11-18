package com.beez.backend.meeting.dto;

import java.time.Instant;
import java.util.List;

/**
 * 명세 00-common MeetingEntry. text 는 DB 의 body.
 * summaryId·throughEntryId·generation 은 kind=SUMMARY 일 때만 채운다(정리 ID, 정리에 쓴 마지막 기록, AI 생성 근거).
 * summaryId 로 회의 끝내기(MTG-A13)·수정 요청(MTG-A12)을 하고, 구조화한 정리는 최신 정리 조회(MTG-A15)로 읽는다.
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
        String summaryId,
        String throughEntryId,
        MeetingGeneration generation,
        Instant createdAt
) {
}
