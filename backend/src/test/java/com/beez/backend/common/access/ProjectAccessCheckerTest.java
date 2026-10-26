package com.beez.backend.common.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRepository;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.TestActors;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/** -172 참고 2 "검사 순서"의 판정표. 응답 매핑이 바뀌면 이 테스트가 먼저 알려 준다. */
class ProjectAccessCheckerTest {

    private static final long PROJECT = 10L;
    private static final long ACCOUNT = 1L;

    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final ProjectAccessChecker checker = new ProjectAccessChecker(members, projects);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void loginAs(Object principal) {
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private void givenMembership(long accountId, long memberId, ProjectRole role) {
        ProjectMember m = mock(ProjectMember.class);
        when(m.getId()).thenReturn(memberId);
        when(m.getRole()).thenReturn(role);
        when(members.findActiveMembership(PROJECT, accountId)).thenReturn(Optional.of(m));
    }

    private static void assertStatus(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }

    @Test
    void noAuthentication_is401() {
        assertStatus(() -> checker.requireMember(PROJECT), HttpStatus.UNAUTHORIZED, "unauthenticated");
        assertStatus(checker::requireUser, HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @Test
    void nonMember_andMissingProject_get_theSame404() {
        loginAs(new SessionUser(ACCOUNT));
        when(members.findActiveMembership(anyLong(), anyLong())).thenReturn(Optional.empty());

        when(projects.existsByIdAndArchivedAtIsNull(PROJECT)).thenReturn(true);
        ApiException notMember = catchApi(() -> checker.requireMember(PROJECT));
        when(projects.existsByIdAndArchivedAtIsNull(PROJECT)).thenReturn(false);
        ApiException missing = catchApi(() -> checker.requireMember(PROJECT));

        assertThat(notMember.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(List.of(notMember.getCode(), notMember.getMessage()))
                .isEqualTo(List.of(missing.getCode(), missing.getMessage()));
    }

    @Test
    void member_onLeaderOnlyCall_is403() {
        loginAs(new SessionUser(ACCOUNT));
        givenMembership(ACCOUNT, 5L, ProjectRole.MEMBER);

        assertStatus(() -> checker.requireMember(PROJECT, ProjectRole.LEADER), HttpStatus.FORBIDDEN, "forbidden");
    }

    @Test
    void leader_passesBothLevels_andActorCarriesIds() {
        loginAs(new SessionUser(ACCOUNT));
        givenMembership(ACCOUNT, 5L, ProjectRole.LEADER);

        assertThat(checker.requireMember(PROJECT)).isEqualTo(
                new ProjectActor(ACCOUNT, 5L, PROJECT, ProjectRole.LEADER, ActorType.HUMAN, null));
        assertThat(checker.requireMember(PROJECT, ProjectRole.LEADER).isLeader()).isTrue();
    }

    @Test
    void agent_forAnotherProjectThanItsToken_is404_withoutDbLookup() {
        loginAs(new AgentPrincipal(ACCOUNT, 99L, 3L));

        assertStatus(() -> checker.requireMember(PROJECT), HttpStatus.NOT_FOUND, "not_found");
        verifyNoInteractions(members, projects);
    }

    @Test
    void agent_withActiveMembership_isAgentActor_butNotAUser() {
        loginAs(new AgentPrincipal(ACCOUNT, PROJECT, 3L));
        givenMembership(ACCOUNT, 5L, ProjectRole.MEMBER);

        assertThat(checker.requireMember(PROJECT)).isEqualTo(
                new ProjectActor(ACCOUNT, 5L, PROJECT, ProjectRole.MEMBER, ActorType.AGENT, 3L));
        // 에이전트 토큰으로 사람 전용(USER) API 를 부를 수 없다
        assertStatus(checker::requireUser, HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @Test
    void actorHelpers_denyAgentsAndOtherMembers_with403() {
        assertStatus(() -> TestActors.agent(PROJECT, 5L, 3L).requireHuman(), HttpStatus.FORBIDDEN, "forbidden");
        // 팀장도 담당자가 아니면 자동 통과하지 않는다 (00-common 2절 TASK_OWNER)
        assertStatus(() -> TestActors.leader(PROJECT, 5L).requireMemberId(6L), HttpStatus.FORBIDDEN, "forbidden");
    }

    private static ApiException catchApi(Runnable call) {
        try {
            call.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("ApiException expected");
    }
}
