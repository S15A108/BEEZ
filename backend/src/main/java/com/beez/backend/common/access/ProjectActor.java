package com.beez.backend.common.access;

import com.beez.backend.project.ProjectRole;

/**
 * {@link ProjectAccessChecker#requireMember} 를 통과한 요청 주체.
 * 작업 담당자 판정·작성자 기록에 memberId 를, 감사 기록에 actorType·agentTokenId 를 쓴다.
 *
 * @param agentTokenId 에이전트 요청이면 토큰 ID, 사람 세션이면 null
 */
public record ProjectActor(
        long accountId,
        long memberId,
        long projectId,
        ProjectRole role,
        ActorType actorType,
        Long agentTokenId) {

    public boolean isLeader() {
        return role == ProjectRole.LEADER;
    }

    public boolean isAgent() {
        return actorType == ActorType.AGENT;
    }

    /** 사람만 할 수 있는 동작(승인·발행·팀 관리, 에이전트 CANCEL 등). 에이전트면 403. */
    public void requireHuman() {
        if (isAgent()) {
            throw AccessErrors.forbidden("human_only");
        }
    }

    /** 작업 담당자·기록 작성자 조건(TASK_OWNER·RECORD_AUTHOR). 다른 멤버면 403. 팀장도 자동으로 통과하지 않는다. */
    public void requireMemberId(long expectedMemberId) {
        if (memberId != expectedMemberId) {
            throw AccessErrors.forbidden("not_owner");
        }
    }
}
