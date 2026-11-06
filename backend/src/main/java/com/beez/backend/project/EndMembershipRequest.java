package com.beez.backend.project;

/**
 * MEM-A09 제외·MEM-A10 나가기 요청. 본문은 생략할 수 있다. ID 는 십진 문자열.
 * reassignToMemberId 는 열린 작업을 넘겨받을 같은 프로젝트의 활성 멤버. 없으면 열린 작업의 담당자만 비운다(미배정, -301).
 */
public record EndMembershipRequest(
        String reassignToMemberId
) {
}
