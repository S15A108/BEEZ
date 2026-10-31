package com.beez.backend.common.access;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRepository;
import com.beez.backend.project.ProjectRole;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 권한 검사 (-170, -172 합의 1·3·4). 각 서비스 메서드의 첫 줄에서 호출한다.
 * 호출자는 {@link CallerContext} 인자로 받는다 — REST 는 컨트롤러 인자 주입, MCP 어댑터는 같은 값을 만들어 넘긴다.
 * <pre>
 * ProjectActor actor = access.requireMember(caller, projectId);                    // MEMBER
 * ProjectActor actor = access.requireMember(caller, projectId, ProjectRole.LEADER); // 팀장 아니면 403
 * </pre>
 * 멤버십·역할은 캐시 없이 매 호출 DB 에서 읽는다. 제외·위임 직후 다음 요청부터 바로 막힌다.
 * 실패는 {@link AccessErrors} 의 {@code ApiException} 으로 던진다.
 */
@Component
public class ProjectAccessChecker {

    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final AgentTokenScopes tokens;
    private final AccountRepository accounts;

    public ProjectAccessChecker(ProjectMemberRepository members, ProjectRepository projects,
                                AgentTokenScopes tokens, AccountRepository accounts) {
        this.members = members;
        this.projects = projects;
        this.tokens = tokens;
        this.accounts = accounts;
    }

    /**
     * USER 권한(프로젝트와 무관한 본인 API). 사람 세션의 활성(탈퇴하지 않은) 계정을 돌려준다.
     * 비로그인·탈퇴한 계정의 세션은 401. 계정을 돌려주므로 호출하는 쪽이 같은 계정을 다시 조회하지 않아도 된다.
     * 에이전트도 401 이다 — 에이전트는 MCP 전용이라 REST 의 USER API 를 부를 인증이 없다 (-172 참고 2의 1).
     */
    public Account requireUser(CallerContext caller) {
        if (caller != null && !caller.isHuman()) {
            throw AccessErrors.agentUnauthenticated();
        }
        if (caller == null || caller.accountId() == null) {
            throw AccessErrors.unauthenticated();
        }
        return accounts.findByIdAndDeletedAtIsNull(caller.accountId()).orElseThrow(AccessErrors::unauthenticated);
    }

    /** MEMBER 권한. 팀장도 통과한다. */
    public ProjectActor requireMember(CallerContext caller, long projectId) {
        return requireMember(caller, projectId, ProjectRole.MEMBER);
    }

    /**
     * required 이상의 역할을 가진 활성 멤버인지 확인한다.
     * 폐기·남의 토큰 401, 다른 프로젝트 토큰·비멤버·삭제 프로젝트 404, 역할 부족 403.
     */
    public ProjectActor requireMember(CallerContext caller, long projectId, ProjectRole required) {
        if (caller == null || caller.accountId() == null) {
            throw caller != null && !caller.isHuman() ? AccessErrors.agentUnauthenticated() : AccessErrors.unauthenticated();
        }
        long accountId = caller.accountId();
        ActorType actorType = ActorType.HUMAN;
        Long agentTokenId = null;
        if (!caller.isHuman()) {
            agentTokenId = caller.agentTokenId();
            requireTokenFor(accountId, agentTokenId, projectId);
            actorType = ActorType.AGENT;
        }

        String who = " project=" + projectId + " account=" + accountId;
        ProjectMember member = members.findActiveMembership(projectId, accountId)
                .orElseThrow(() -> denyWithoutMembership(caller.isHuman(), accountId, projectId, who));

        if (!member.getRole().satisfies(required)) {
            throw AccessErrors.forbidden("role_required_" + required.name().toLowerCase() + who, "팀장만 할 수 있습니다");
        }
        return new ProjectActor(accountId, member.getId(), projectId, member.getRole(), actorType, agentTokenId);
    }

    /**
     * 사람 세션의 팀장만 통과 (승인·발행·팀 관리, 담당자 변경). 팀장 토큰으로 접속한 에이전트도 403 이다 (00-common 2절).
     * 비멤버 404 → 팀장 아님 403 → 에이전트 403 순으로 본다.
     */
    public ProjectActor requireHumanLeader(CallerContext caller, long projectId) {
        ProjectActor actor = requireMember(caller, projectId, ProjectRole.LEADER);
        actor.requireHuman();
        return actor;
    }

    /**
     * 활성 멤버십이 없을 때의 거부. 실패할 때만 부르므로 성공 경로의 쿼리는 늘지 않는다.
     * 사람 세션인데 계정이 탈퇴했으면 세션 자체가 무효라 401 ({@code /api/me} 의 AccountService.getMe 와 같은 판정).
     * 그 밖은 존재를 숨기는 404.
     */
    private RuntimeException denyWithoutMembership(boolean human, long accountId, long projectId, String who) {
        if (human && accounts.findByIdAndDeletedAtIsNull(accountId).isEmpty()) {
            return AccessErrors.unauthenticated();
        }
        return AccessErrors.notFound(
                (projects.existsByIdAndArchivedAtIsNull(projectId) ? "not_member" : "project_missing") + who);
    }

    /** 멤버 번호가 이 프로젝트의 활성 멤버인지 (담당자 지정·변경 대상 확인). */
    public boolean isActiveMember(long memberId, long projectId) {
        return members.isActiveMember(memberId, projectId);
    }

    /**
     * 프로젝트 행을 잠근 뒤 요청자가 아직 활성 팀장인지 다시 본다 (MEM-A08 "요청 처리 시점의 팀장 권한 재확인").
     * 팀장 위임이 같은 행을 잠그므로, 잠금을 기다리는 동안 위임이 커밋됐으면 이전 팀장의 요청은 여기서 403 이다.
     * {@link #requireMember} 를 다시 부르지 않는 이유: 앞서 읽은 멤버십 엔티티가 영속성 컨텍스트에 남아 옛 역할 그대로 돌아온다.
     * 그래서 엔티티가 아니라 값(역할)을 DB 에서 읽는다. 팀장 전용 변경 요청은 프로젝트 행 잠금 직후 이걸 부른다.
     */
    public void requireLeaderAfterLock(ProjectActor actor) {
        if (!members.isActiveLeader(actor.memberId(), actor.projectId())) {
            throw AccessErrors.forbidden("leader_changed project=" + actor.projectId() + " member=" + actor.memberId(),
                    "팀장만 할 수 있습니다");
        }
    }

    /**
     * 토큰이 이 계정 것이고 폐기되지 않았는지(아니면 401), 이 프로젝트 것인지(아니면 404).
     * 토큰 검증 필터(-175)가 먼저 보지만, 서비스 쪽 마지막 방어로 매번 확인한다.
     */
    private void requireTokenFor(long accountId, Long agentTokenId, long projectId) {
        if (agentTokenId == null) {
            throw AccessErrors.agentUnauthenticated();
        }
        AgentTokenScopes.TokenScope scope = tokens.find(agentTokenId, accountId)
                .orElseThrow(() -> AccessErrors.tokenRevoked("token_missing token=" + agentTokenId));
        if (scope.revoked()) {
            throw AccessErrors.tokenRevoked("token_revoked token=" + agentTokenId);
        }
        if (scope.projectId() != projectId) {
            // 토큰의 프로젝트가 기준이다. 요청의 project_id 는 대조만 한다.
            throw AccessErrors.notFound("token_project_mismatch project=" + projectId
                    + " token_project=" + scope.projectId() + " token=" + agentTokenId);
        }
    }
}
