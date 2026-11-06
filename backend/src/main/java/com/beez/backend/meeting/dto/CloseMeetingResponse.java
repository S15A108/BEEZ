package com.beez.backend.meeting.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * MTG-A13 응답. changeProposalId 는 정리에 바꿀 문서가 있어 제안을 만든 때만 있다(없으면 필드 생략).
 * notifiedMemberIds 는 알림을 받은 관련 작업 담당자의 프로젝트 멤버 ID — 받은 사람이 없으면 빈 배열.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CloseMeetingResponse(MeetingResponse meeting, String changeProposalId, List<String> notifiedMemberIds) {
}
