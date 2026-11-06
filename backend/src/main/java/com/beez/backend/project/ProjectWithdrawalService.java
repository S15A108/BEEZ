package com.beez.backend.project;

import com.beez.backend.agenttoken.AgentTokenRepository;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.service.TaskHandoverService;
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
 *   <li>팀원으로 참여한 프로젝트는 멤버십을 끝낸다(LEFT). 맡은 열린 작업은 인계 대상 없는 나가기(MEM-A10)와 같이 담당자만 비운다
 *       (S15P31A108-281, 미배정 — 상태·검증은 그대로, 작업마다 TASK_ASSIGNEE_CHANGED 활동). 팀장이 TSK-A06 으로 정하거나
 *       팀원이 스스로 맡는다. 탈퇴는 인계 대상을 받지 않는다. 나가기와 같이 MEMBER_LEFT 활동을 남긴다(-289). 함께 삭제하는
 *       혼자 쓰는 프로젝트에는 남기지 않는다 — 볼 사람이 없다.</li>
 * </ul>
 * 잠금 순서는 팀원 관리(ProjectMemberService)와 같은 프로젝트 행 → 멤버십 행 → 작업(lock_version)이다. 프로젝트가 여럿이면
 * id 순서로 잠가 교착을 피한다.
 */
@Service
public class ProjectWithdrawalService {

    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final AgentTokenRepository tokens;
    private final TaskHandoverService handover;
    private final ProjectActivities activities;
    private final EntityManager em;

    public ProjectWithdrawalService(ProjectMemberRepository members, ProjectRepository projects,
                                    AgentTokenRepository tokens, TaskHandoverService handover,
                                    ProjectActivities activities, EntityManager em) {
        this.members = members;
        this.projects = projects;
        this.tokens = tokens;
        this.handover = handover;
        this.activities = activities;
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
     * 담당자를 비우는 동안 그 작업 상태가 동시에 바뀌면 409 revision_conflict 로 탈퇴 전체가 되돌아간다(다시 시도하면 된다).
     *
     * @param caller 탈퇴하는 본인. 담당자를 비운 활동·MEMBER_LEFT 의 행위자다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void leaveAll(CallerContext caller, long accountId, Instant now) {
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
            } else {
                // 팀 프로젝트의 팀장은 위에서 막혔으니 여기는 팀원뿐이다. 나가기(ProjectMemberService.end)와 같이
                // 담당자 비우기(작업 flush·활동) → 멤버십 종료 순서다
                handover.handOver(caller, row.projectId(), member.getId(), null);
            }
            member.end(MembershipEndReason.LEFT, now);
            members.saveAndFlush(member);
            if (!row.soloLeader()) {
                activities.membershipEnded(caller, member, MembershipEndReason.LEFT);
            }
        }
    }
}
