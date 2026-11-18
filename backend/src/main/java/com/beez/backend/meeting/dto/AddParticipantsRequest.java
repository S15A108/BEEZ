package com.beez.backend.meeting.dto;

import java.util.List;

/** MTG-A04 요청. reason 은 알림 문구용이라 저장하지 않는다 (알림 연결은 S15P31A108-237) */
public record AddParticipantsRequest(List<String> memberIds, String reason) {
}
