package com.beez.backend.meeting.dto;

/** 비비 기록이 가리키는 대상 (type: TASK|DESIGN_ITEM|DOCUMENT_VERSION) */
public record MeetingRef(String type, String id, String label) {
}
