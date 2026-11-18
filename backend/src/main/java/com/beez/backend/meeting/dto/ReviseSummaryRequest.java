package com.beez.backend.meeting.dto;

/** MTG-A12 요청. 본문 검증은 Origin 검사 뒤에 서비스에서 한다 */
public record ReviseSummaryRequest(String feedback) {
}
