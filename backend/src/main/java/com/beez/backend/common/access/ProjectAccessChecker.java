package com.beez.backend.common.access;

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

    public ProjectAccessChecker(ProjectMemberRepository members, ProjectRepository projects,
                                AgentTokenScopes tokens) {
        this.members = members;
        this.projects = projects;
        this.tokens = tokens;
    }

    /** USER 권한(프로젝트와 무관한 본인 API). 사람 세션의 계정 ID. 에이전트·비로그인은 401. */
    public long requireUser(CallerContext caller) {
        if (caller == null || !caller.isHuman()) {
            throw AccessErrors.unauthenticated();
        }
        return caller.accountId();
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
            throw AccessErrors.unauthenticated();
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
                .orElseThrow(() -> AccessErrors.notFound(
                        (projects.existsByIdAndArchivedAtIsNull(projectId) ? "not_member" : "project_missing") + who));

        if (!member.getRole().satisfies(required)) {
            throw AccessErrors.forbidden("role_required_" + required.name().toLowerCase() + who);
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

    /** 멤버 번호가 이 프로젝트의 활성 멤버인지 (담당자 지정·변경 대상 확인). */
    public boolean isActiveMember(long memberId, long projectId) {
        return members.isActiveMember(memberId, projectId);
    }

    /**
     * 토큰이 이 계정 것이고 폐기되지 않았는지(아니면 401), 이 프로젝트 것인지(아니면 404).
     * 토큰 검증 필터(-175)가 먼저 보지만, 서비스 쪽 마지막 방어로 매번 확인한다.
     */
    private void requireTokenFor(long accountId, Long agentTokenId, long projectId) {
        if (agentTokenId == null) {
            throw AccessErrors.unauthenticated();
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
