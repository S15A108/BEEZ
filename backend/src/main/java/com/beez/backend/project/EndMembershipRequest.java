package com.beez.backend.project;

/**
 * MEM-A09 제외·MEM-A10 나가기 요청. 본문은 생략할 수 있다. ID 는 십진 문자열.
 * reassignToMemberId 는 열린 작업을 넘겨받을 같은 프로젝트의 활성 멤버 — 열린 작업이 있는데 없으면 409 active_tasks_exist.
 */
public record EndMembershipRequest(
        String reassignToMemberId
) {
}
