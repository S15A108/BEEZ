package com.beez.backend.project;

import com.beez.backend.agenttoken.AgentTokenRepository;
import com.beez.backend.common.api.ApiException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 탈퇴의 프로젝트 쪽 처리 (S15P31A108-171, ACC-A10·A13). 계정 쪽(개인정보 삭제·토큰 폐기)은 AccountWithdrawalService 가 한다.
 * <ul>
 *   <li>다른 활성 팀원이 있는 프로젝트의 팀장이면 409 leader_of_team_projects — 위임(MEM-A08)이나 삭제(PRJ-A05)가 먼저다.</li>
 *   <li>혼자 쓰는 프로젝트(팀장, 다른 활성 팀원 없음)는 함께 삭제한다 — PRJ-A05 와 같이 archived_at 과 토큰 전부 폐기.</li>
 *   <li>팀원으로 참여한 프로젝트는 멤버십을 끝낸다(LEFT). 맡은 열린 작업은 담당자를 그대로 둔다 — 기록은 '탈퇴한 사용자'로 남고
 *       팀장이 담당자 변경(TSK-A06)으로 넘긴다. 나가기(MEM-A10)와 달리 인계 대상을 받지 않는다.</li>
 * </ul>
 * 잠금 순서는 팀원 관리(ProjectMemberService)와 같은 프로젝트 행 → 멤버십 행이다. 프로젝트가 여럿이면 id 순서로 잠가 교착을 피한다.
 */
@Service
public class ProjectWithdrawalService {

    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final AgentTokenRepository tokens;
    private final EntityManager em;

    public ProjectWithdrawalService(ProjectMemberRepository members, ProjectRepository projects,
                                    AgentTokenRepository tokens, EntityManager em) {
        this.members = members;
        this.projects = projects;
        this.tokens = tokens;
        this.em = em;
    }

    /** ACC-A13. 탈퇴를 막는 프로젝트. 참고용이라 잠그지 않는다 — 최종 판정은 {@link #leaveAll} 이 잠근 뒤 다시 한다. */
    @Transactional(readOnly = true)
    public List<BlockedProjectResponse> blockedProjects(long accountId) {
        return members.findMembershipsForWithdrawal(accountId).stream()
                .filter(WithdrawalMembershipRow::blocksWithdrawal)
                .map(BlockedProjectResponse::of)
                .toList();
    }

    /**
     * ACC-A10. 이 계정의 모든 활성 멤버십을 정리한다. 막히는 프로젝트가 있으면 아무것도 바꾸지 않고 409.
     * 계정 탈퇴와 같은 트랜잭션이어야 해서 바깥 트랜잭션을 요구한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaveAll(long accountId, Instant now) {
        // 1) 지금 참여 중인 프로젝트 행을 id 순서로 잠근다. 잠그는 동안 위임·제외·삭제가 끝났을 수 있어 판정은 잠근 뒤에 한다
        for (WithdrawalMembershipRow row : members.findMembershipsForWithdrawal(accountId)) {
            projects.findForUpdate(row.projectId());
        }
        // 2) 잠근 상태에서 다시 읽어 판정한다. 잠금 전에 위임받아 팀장이 됐으면 여기서 막힌다
        List<WithdrawalMembershipRow> rows = members.findMembershipsForWithdrawal(accountId);
        List<BlockedProjectResponse> blocked = rows.stream()
                .filter(WithdrawalMembershipRow::blocksWithdrawal)
                .map(BlockedProjectResponse::of)
                .toList();
        if (!blocked.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "leader_of_team_projects",
                    "팀원이 있는 프로젝트의 팀장입니다. 팀장을 위임하거나 프로젝트를 삭제한 뒤에 탈퇴할 수 있습니다",
                    Map.of("blocked_projects", blocked));
        }
        // 1) 과 2) 사이에 새로 참여한 프로젝트는 잠그지 않은 채 정리된다. 커밋 뒤에는 탈퇴 계정이라 권한 검사가 막는다
        for (WithdrawalMembershipRow row : rows) {
            ProjectMember member = em.find(ProjectMember.class, row.memberId());
            // 토큰 발급(AGT-A01)이 멤버십 행을 잠그므로, 잠가 두면 끝난 멤버십에 새 토큰이 생기지 않는다
            em.refresh(member, LockModeType.PESSIMISTIC_WRITE);
            if (row.soloLeader()) {
                projects.findById(row.projectId()).ifPresent(p -> p.archive(now));
                tokens.revokeAllInProject(row.projectId(), now);
            }
            member.end(MembershipEndReason.LEFT, now);
            members.saveAndFlush(member);
        }
    }
}
