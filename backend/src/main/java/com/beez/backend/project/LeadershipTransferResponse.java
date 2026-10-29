package com.beez.backend.project;

import java.util.List;

/** MEM-A08 응답. 권한이 바뀐 두 멤버 — 이전 팀장(MEMBER), 새 팀장(LEADER) 순서 */
public record LeadershipTransferResponse(List<MemberResponse> members) {
}
