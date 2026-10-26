package com.beez.backend.common.access;

import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRepository;
import com.beez.backend.project.ProjectRole;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 권한 검사 (-170, -172 합의 1·3·4). 각 서비스 메서드의 첫 줄에서 호출한다.
 * <pre>
 * ProjectActor actor = access.requireMember(projectId);                    // MEMBER
 * ProjectActor actor = access.requireMember(projectId, ProjectRole.LEADER); // 팀장 아니면 403
 * </pre>
 * 멤버십·역할은 캐시 없이 매 호출 DB 에서 읽는다. 제외·위임 직후 다음 요청부터 바로 막힌다.
 * 실패는 {@link AccessErrors} 의 {@code ApiException} 으로 던진다.
 */
@Component
public class ProjectAccessChecker {

    private final ProjectMemberRepository members;
    private final ProjectRepository projects;

    public ProjectAccessChecker(ProjectMemberRepository members, ProjectRepository projects) {
        this.members = members;
        this.projects = projects;
    }

    /** USER 권한(프로젝트와 무관한 본인 API). 사람 세션의 계정 ID. 없으면 401. */
    public long requireUser() {
        if (currentPrincipal() instanceof SessionUser user) {
            return user.accountId();
        }
        throw AccessErrors.unauthenticated();
    }

    /** MEMBER 권한. 팀장도 통과한다. */
    public ProjectActor requireMember(long projectId) {
        return requireMember(projectId, ProjectRole.MEMBER);
    }

    /** required 이상의 역할을 가진 활성 멤버인지 확인한다. 비멤버·삭제 프로젝트 404, 역할 부족 403. */
    public ProjectActor requireMember(long projectId, ProjectRole required) {
        Object principal = currentPrincipal();
        long accountId;
        ActorType actorType;
        Long agentTokenId;
        if (principal instanceof SessionUser user) {
            accountId = user.accountId();
            actorType = ActorType.HUMAN;
            agentTokenId = null;
        } else if (principal instanceof AgentPrincipal agent) {
            // 토큰의 프로젝트가 기준이다. 인자로 받은 project_id 는 대조만 한다.
            if (agent.projectId() != projectId) {
                throw AccessErrors.notFound("token_project_mismatch project=" + projectId
                        + " token_project=" + agent.projectId() + " token=" + agent.agentTokenId());
            }
            accountId = agent.accountId();
            actorType = ActorType.AGENT;
            agentTokenId = agent.agentTokenId();
        } else {
            throw AccessErrors.unauthenticated();
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

    private static Object currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getPrincipal();
    }
}
