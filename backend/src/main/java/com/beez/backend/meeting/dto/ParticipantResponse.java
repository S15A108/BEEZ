package com.beez.backend.meeting.dto;

/** 명세 00-common Participant. role·addedByMemberId 가 없으면 생략된다 */
public record ParticipantResponse(String memberId, String status, String role, String addedByMemberId) {
}
