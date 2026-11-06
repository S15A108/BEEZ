package com.beez.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.invitation.InvitationService.AutoJoin;
import com.beez.backend.project.MembershipEndReason;
import com.beez.backend.project.ProjectMember;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 자동 참여 판정 (Q05 4, -220). 끝난 이유별 규칙만 빠르게 본다 — 잠금·저장은 InvitationAutoJoinIntegrationTests.
 * 깨지면: 스스로 나간 사람이 로그인만으로 다시 들어오거나, 팀장이 제외한 사람이 다시 초대받아도 들어오지 못한다.
 */
class AutoJoinRuleTest {

    @Test
    void endReasonDecidesAutoJoin() {
        assertThat(InvitationService.autoJoinAction(Optional.empty())).isEqualTo(AutoJoin.JOIN);
        assertThat(InvitationService.autoJoinAction(Optional.of(ProjectMember.member(1, 2))))
                .isEqualTo(AutoJoin.ALREADY_ACTIVE);
        assertThat(InvitationService.autoJoinAction(Optional.of(ended(MembershipEndReason.REMOVED))))
                .isEqualTo(AutoJoin.REVIVE);
        assertThat(InvitationService.autoJoinAction(Optional.of(ended(MembershipEndReason.LEFT))))
                .isEqualTo(AutoJoin.SKIP);
    }

    /** 깨지면: 이유를 모르는 옛 비활성 멤버십(V11 전에 끝남)이 로그인만으로 되살아난다 — 모르면 본인이 링크를 여는 쪽으로 둔다. */
    @Test
    void unknownEndReason_isNotRevivedByLogin() {
        assertThat(InvitationService.autoJoinAction(Optional.of(ended(null)))).isEqualTo(AutoJoin.SKIP);
    }

    private static ProjectMember ended(MembershipEndReason reason) {
        ProjectMember m = ProjectMember.member(1, 2);
        m.end(reason, Instant.now());
        return m;
    }
}
