package com.beez.backend.project;

/**
 * MEM-A09 제외·MEM-A10 나가기 요청. 본문은 생략할 수 있다. ID 는 십진 문자열.
 * reassignToMemberId 는 열린 작업을 넘겨받을 같은 프로젝트의 활성 멤버 — 열린 작업이 있는데 없으면 409 active_tasks_exist.
 * transferLeaderToMemberId 는 팀장이 나갈 때 팀장을 넘겨받을 같은 프로젝트의 활성 멤버(MEM-A10 전용, S15P31A108-304).
 * 팀원이 보내거나 제외(MEM-A09)에 보내면 무시한다.
 */
public record EndMembershipRequest(
        String reassignToMemberId,
        String transferLeaderToMemberId
) {
}
