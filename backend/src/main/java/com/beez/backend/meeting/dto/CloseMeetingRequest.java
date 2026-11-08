package com.beez.backend.meeting.dto;

/** MTG-A13 요청. summaryId 는 팀장이 확인한 최신 정리(십진 문자열) */
public record CloseMeetingRequest(String summaryId) {
}
