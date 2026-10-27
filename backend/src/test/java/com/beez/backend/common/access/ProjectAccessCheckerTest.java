package com.beez.backend.common.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.access.AgentTokenScopes.TokenScope;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.project.ProjectRepository;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.TestActors;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** -172 참고 2 "검사 순서"의 판정표. 응답 매핑이 바뀌면 이 테스트가 먼저 알려 준다. */
class ProjectAccessCheckerTest {

    private static final long PROJECT = 10L;
    private static final long ACCOUNT = 1L;
    private static final long TOKEN = 3L;

    private static final CallerContext HUMAN = CallerContext.human(ACCOUNT);
    private static final CallerContext AGENT = CallerContext.agent(ACCOUNT, TOKEN);

    private final ProjectMemberRepository members = mock(ProjectMemberRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final AgentTokenScopes tokens = mock(AgentTokenScopes.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final ProjectAccessChecker checker = new ProjectAccessChecker(members, projects, tokens, accounts);

    private void givenMembership(long accountId, long memberId, ProjectRole role) {
        ProjectMember m = mock(ProjectMember.class);
        when(m.getId()).thenReturn(memberId);
        when(m.getRole()).thenReturn(role);
        when(members.findActiveMembership(PROJECT, accountId)).thenReturn(Optional.of(m));
    }

    private void givenToken(long projectId, boolean revoked) {
        when(tokens.find(TOKEN, ACCOUNT)).thenReturn(Optional.of(new TokenScope(projectId, revoked)));
    }

    private static void assertStatus(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(status);
                    assertThat(e.getCode()).isEqualTo(code);
                });
    }

    @Test
    void noCaller_is401() {
        assertStatus(() -> checker.requireMember(null, PROJECT), HttpStatus.UNAUTHORIZED, "unauthenticated");
        assertStatus(() -> checker.requireUser(null), HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @Test
    void nonMember_andMissingProject_get_theSame404() {
        when(accounts.findByIdAndDeletedAtIsNull(ACCOUNT)).thenReturn(Optional.of(mock(Account.class)));
        when(members.findActiveMembership(anyLong(), anyLong())).thenReturn(Optional.empty());

        when(projects.existsByIdAndArchivedAtIsNull(PROJECT)).thenReturn(true);
        ApiException notMember = catchApi(() -> checker.requireMember(HUMAN, PROJECT));
        when(projects.existsByIdAndArchivedAtIsNull(PROJECT)).thenReturn(false);
        ApiException missing = catchApi(() -> checker.requireMember(HUMAN, PROJECT));

        assertThat(notMember.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(List.of(notMember.getCode(), notMember.getMessage()))
                .isEqualTo(List.of(missing.getCode(), missing.getMessage()));
    }

    @Test
    void member_onLeaderOnlyCall_is403() {
        givenMembership(ACCOUNT, 5L, ProjectRole.MEMBER);

        // -172 합의 3: 403 은 code 하나에 사유 메시지를 담는다 (404 와 달리 숨기지 않는다)
        assertThat(catchApi(() -> checker.requireMember(HUMAN, PROJECT, ProjectRole.LEADER)))
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(e.getCode()).isEqualTo("forbidden");
                    assertThat(e.getMessage()).isEqualTo("팀장만 할 수 있습니다");
                });
    }

    @Test
    void leader_passesBothLevels_andActorCarriesIds() {
        givenMembership(ACCOUNT, 5L, ProjectRole.LEADER);

        assertThat(checker.requireMember(HUMAN, PROJECT)).isEqualTo(
                new ProjectActor(ACCOUNT, 5L, PROJECT, ProjectRole.LEADER, ActorType.HUMAN, null));
        assertThat(checker.requireMember(HUMAN, PROJECT, ProjectRole.LEADER).isLeader()).isTrue();
    }

    @Test
    void agent_forAnotherProjectThanItsToken_is404_beforeMembershipLookup() {
        givenToken(99L, false);

        assertStatus(() -> checker.requireMember(AGENT, PROJECT), HttpStatus.NOT_FOUND, "not_found");
        verifyNoInteractions(members, projects);
    }

    @Test
    void agent_withRevokedOrSomeoneElsesToken_is401() {
        givenToken(PROJECT, true);
        assertStatus(() -> checker.requireMember(AGENT, PROJECT), HttpStatus.UNAUTHORIZED, "token_revoked");

        when(tokens.find(TOKEN, ACCOUNT)).thenReturn(Optional.empty());
        assertStatus(() -> checker.requireMember(AGENT, PROJECT), HttpStatus.UNAUTHORIZED, "token_revoked");
        verifyNoInteractions(members, projects);
    }

    @Test
    void agent_withActiveMembership_isAgentActor_butNotAUser() {
        givenToken(PROJECT, false);
        givenMembership(ACCOUNT, 5L, ProjectRole.MEMBER);

        assertThat(checker.requireMember(AGENT, PROJECT)).isEqualTo(
                new ProjectActor(ACCOUNT, 5L, PROJECT, ProjectRole.MEMBER, ActorType.AGENT, TOKEN));
        // 에이전트 토큰으로 사람 전용(USER) API 를 부를 수 없다
        assertStatus(() -> checker.requireUser(AGENT), HttpStatus.UNAUTHORIZED, "unauthenticated");
        assertThat(catchApi(() -> checker.requireUser(AGENT)).getMessage()).isEqualTo(AccessErrors.AGENT_UNAUTHENTICATED_MESSAGE);
    }

    @Test
    void withdrawnAccountsSession_is401_notHiddenAs404() {
        // 탈퇴한 계정의 세션이 남아 있으면 프로젝트 API 도 /api/me 처럼 401 (세션 자체가 무효)
        when(members.findActiveMembership(anyLong(), anyLong())).thenReturn(Optional.empty());
        when(accounts.findByIdAndDeletedAtIsNull(ACCOUNT)).thenReturn(Optional.empty());

        assertStatus(() -> checker.requireMember(HUMAN, PROJECT), HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @Test
    void requireUser_withdrawnAccountsSession_is401() {
        // requireUser 만 부르는 USER API(/api/me, 초대 수락 등)도 탈퇴 계정의 세션을 막아야 한다
        when(accounts.findByIdAndDeletedAtIsNull(ACCOUNT)).thenReturn(Optional.empty());

        assertThat(catchApi(() -> checker.requireUser(HUMAN))).satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(e.getMessage()).isEqualTo(AccessErrors.UNAUTHENTICATED_MESSAGE);
        });
    }

    @Test
    void requireUser_humanCallerWithoutAccountId_is401_not500() {
        assertStatus(() -> checker.requireUser(CallerContext.human(null)), HttpStatus.UNAUTHORIZED, "unauthenticated");
    }

    @Test
    void actorHelpers_denyAgentsAndOtherMembers_with403() {
        assertStatus(() -> TestActors.agent(PROJECT, 5L, 3L).requireHuman(), HttpStatus.FORBIDDEN, "forbidden");
        // 팀장도 담당자가 아니면 자동 통과하지 않는다 (00-common 2절 TASK_OWNER)
        assertStatus(() -> TestActors.leader(PROJECT, 5L).requireMemberId(6L), HttpStatus.FORBIDDEN, "forbidden");
        // 담당자가 비어 있는 작업도 403 (task.assignee_member_id 는 NULL 허용)
        assertStatus(() -> TestActors.member(PROJECT, 5L).requireMemberId(null), HttpStatus.FORBIDDEN, "forbidden");
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
