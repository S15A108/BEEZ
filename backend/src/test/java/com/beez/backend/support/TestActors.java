package com.beez.backend.support;

import com.beez.backend.common.access.ActorType;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.project.ProjectRole;

/**
 * 서비스 단위 테스트에서 ProjectAccessChecker 를 mock 할 때 돌려줄 주체.
 * <pre>
 * when(access.requireMember(PROJECT_ID)).thenReturn(TestActors.member(PROJECT_ID, 7L));
 * when(access.requireMember(PROJECT_ID)).thenThrow(AccessErrors.notFound("not_member"));
 * </pre>
 */
public final class TestActors {

    private TestActors() {
    }

    public static ProjectActor member(long projectId, long memberId) {
        return new ProjectActor(1000 + memberId, memberId, projectId, ProjectRole.MEMBER, ActorType.HUMAN, null);
    }

    public static ProjectActor leader(long projectId, long memberId) {
        return new ProjectActor(1000 + memberId, memberId, projectId, ProjectRole.LEADER, ActorType.HUMAN, null);
    }

    public static ProjectActor agent(long projectId, long memberId, long agentTokenId) {
        return new ProjectActor(1000 + memberId, memberId, projectId, ProjectRole.MEMBER, ActorType.AGENT, agentTokenId);
    }
}
