package com.beez.backend.project;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 회원 탈퇴 판정 규칙 (ACC-A10 처리 규칙, 00-common BlockedProject). */
class WithdrawalMembershipRowTest {

    private static WithdrawalMembershipRow row(ProjectRole role, long others) {
        return new WithdrawalMembershipRow(1, 2, "p", role, others);
    }

    /**
     * 깨지면: 명세의 막는 기준이 바뀐다 — 팀원이 남은 프로젝트의 팀장이 탈퇴하거나(팀장 없는 프로젝트),
     * 혼자 쓰는 프로젝트·팀원으로만 참여한 프로젝트가 탈퇴를 막는다.
     */
    @Test
    void onlyLeaderWithOtherActiveMembersBlocks_soloLeaderIsDeletedInstead() {
        assertThat(row(ProjectRole.LEADER, 1).blocksWithdrawal()).isTrue();
        assertThat(row(ProjectRole.LEADER, 0).blocksWithdrawal()).isFalse();
        assertThat(row(ProjectRole.LEADER, 0).soloLeader()).isTrue();
        assertThat(row(ProjectRole.MEMBER, 3).blocksWithdrawal()).isFalse();
        assertThat(row(ProjectRole.MEMBER, 0).soloLeader()).as("팀원만 남은 프로젝트를 지우지 않는다").isFalse();
    }
}
