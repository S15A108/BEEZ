package com.beez.backend.project;

/** 탈퇴 판정 한 줄 (ProjectMemberRepository#findMembershipsForWithdrawal). otherActiveMembers 는 본인 말고 활성 팀원 수. */
public record WithdrawalMembershipRow(long memberId, long projectId, String projectName, ProjectRole role,
                                      long otherActiveMembers) {

    /** 다른 활성 팀원이 있는 프로젝트의 팀장이면 탈퇴를 막는다 (ACC-A10 처리 규칙). */
    public boolean blocksWithdrawal() {
        return role == ProjectRole.LEADER && otherActiveMembers > 0;
    }

    /** 혼자 쓰는 프로젝트: 탈퇴하면 함께 삭제한다 (ACC-A10 회원 탈퇴 화면 기본안). */
    public boolean soloLeader() {
        return role == ProjectRole.LEADER && otherActiveMembers == 0;
    }
}
