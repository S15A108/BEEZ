package com.beez.backend.common.access;

/**
 * 호출자의 프로젝트 안 신분.
 *
 * @param memberId  project_member.id — 작업 담당자(assignee_member_id)로 쓰는 번호
 * @param role      LEADER 또는 MEMBER
 */
public record Membership(Long memberId, String role) {

    public boolean isLeader() {
        return "LEADER".equals(role);
    }
}
