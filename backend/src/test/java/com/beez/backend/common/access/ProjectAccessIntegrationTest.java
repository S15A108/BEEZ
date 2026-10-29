package com.beez.backend.common.access;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.auth.agent.AgentTokenException;
import com.beez.backend.auth.agent.AgentTokenVerifier;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * requireMember 가 실제 쿼리·실제 필터 체인 위에서 -172 참고 2 대로 막는지. Jira -170 완료 조건 "무권한 테스트".
 * 테스트 전용 컨트롤러(Probe)는 이 클래스에만 붙는다.
 */
@Import(ProjectAccessIntegrationTest.ProbeConfig.class)
class ProjectAccessIntegrationTest extends IntegrationTestSupport {

    /**
     * "agent-{accountId}-{projectId}-{tokenId}" 형식만 통과시키는 토큰 검증 대역. 실제 검증은 AgentTokenApiIntegrationTests.
     * 폐기 여부는 보지 않는다 — 필터가 놓친 폐기 토큰을 권한 검사가 막는지 확인하기 위해서다.
     */
    static final String VALID_PREFIX = "agent-";

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        Probe probe(ProjectAccessChecker access) {
            return new Probe(access);
        }

        @Bean
        @Primary
        AgentTokenVerifier stubAgentTokenVerifier() {
            return raw -> {
                if (!raw.startsWith(VALID_PREFIX)) {
                    throw new AgentTokenException("token_revoked", "stub");
                }
                String[] parts = raw.split("-");
                return new AgentPrincipal(Long.parseLong(parts[1]), Long.parseLong(parts[2]), Long.parseLong(parts[3]));
            };
        }
    }

    @RestController
    static class Probe {

        private final ProjectAccessChecker access;

        Probe(ProjectAccessChecker access) {
            this.access = access;
        }

        @GetMapping("/api/test-probe/projects/{id}")
        ProjectActor member(CallerContext caller, @PathVariable long id) {
            return access.requireMember(caller, id);
        }

        @GetMapping("/api/test-probe/projects/{id}/leader")
        ProjectActor leader(CallerContext caller, @PathVariable long id) {
            return access.requireMember(caller, id, ProjectRole.LEADER);
        }

        @GetMapping("/mcp/test-probe/projects/{id}")
        ProjectActor agent(CallerContext caller, @PathVariable long id) {
            return access.requireMember(caller, id);
        }
    }

    private ApiClient loginAs(long accountId) {
        return client().login(fixtures().emailOf(accountId), AccessFixtures.PASSWORD);
    }

    private static void assertNotFound(HttpResponse<String> res) {
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("\"code\":\"not_found\"").contains(AccessErrors.NOT_FOUND_MESSAGE);
    }

    @Test
    void leader_passes_andActorIsSerializedForCallers() {
        var p = fixtures().projectWithLeader();

        HttpResponse<String> res = loginAs(p.leaderAccountId()).get("/api/test-probe/projects/" + p.projectId() + "/leader");

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"member_id\":" + p.leaderMemberId()).contains("\"role\":\"LEADER\"")
                .contains("\"actor_type\":\"HUMAN\"");
    }

    @Test
    void otherProjectsLeader_getsSame404_asMissingProject() {
        var mine = fixtures().projectWithLeader();
        var theirs = fixtures().projectWithLeader();
        ApiClient me = loginAs(mine.leaderAccountId());

        assertNotFound(me.get("/api/test-probe/projects/" + theirs.projectId()));
        assertNotFound(me.get("/api/test-probe/projects/999999999"));
    }

    @Test
    void member_onLeaderOnlyCall_is403() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient bobClient = loginAs(bob);

        assertThat(bobClient.get("/api/test-probe/projects/" + p.projectId()).statusCode()).isEqualTo(200);
        HttpResponse<String> res = bobClient.get("/api/test-probe/projects/" + p.projectId() + "/leader");
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("\"code\":\"forbidden\"");
    }

    @Test
    void removedMember_isBlockedFromTheNextRequest_sameSession() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        long bobMember = fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient bobClient = loginAs(bob);
        assertThat(bobClient.get("/api/test-probe/projects/" + p.projectId()).statusCode()).isEqualTo(200);

        fixtures().removeMember(bobMember);

        assertNotFound(bobClient.get("/api/test-probe/projects/" + p.projectId()));
    }

    @Test
    void archivedProject_is404_evenForItsLeader() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = loginAs(p.leaderAccountId());

        fixtures().archiveProject(p.projectId());

        assertNotFound(leader.get("/api/test-probe/projects/" + p.projectId()));
    }

    @Test
    void mcp_withoutToken_or_withRejectedToken_is401() {
        HttpResponse<String> noToken = client().get("/mcp/test-probe/projects/1");
        assertThat(noToken.statusCode()).isEqualTo(401);
        assertThat(noToken.body()).contains("\"code\":\"unauthenticated\"")
                .contains(AccessErrors.AGENT_UNAUTHENTICATED_MESSAGE);

        HttpResponse<String> revoked = client().get("/mcp/test-probe/projects/1", "Authorization", "Bearer revoked");
        assertThat(revoked.statusCode()).isEqualTo(401);
        assertThat(revoked.body()).contains("\"code\":\"token_revoked\"");
    }

    private static String bearer(long accountId, long projectId, long tokenId) {
        return "Bearer " + VALID_PREFIX + accountId + "-" + projectId + "-" + tokenId;
    }

    @Test
    void mcp_agentActor_isScopedToItsTokenProject() {
        var p = fixtures().projectWithLeader();
        var other = fixtures().projectWithLeader();
        long tokenId = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        String token = bearer(p.leaderAccountId(), p.projectId(), tokenId);

        HttpResponse<String> own = client().get("/mcp/test-probe/projects/" + p.projectId(), "Authorization", token);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(own.body()).contains("\"actor_type\":\"AGENT\"").contains("\"agent_token_id\":" + tokenId);

        assertNotFound(client().get("/mcp/test-probe/projects/" + other.projectId(), "Authorization", token));
    }

    @Test
    void mcp_tokenRevokedInDb_is401_evenIfTheFilterLetItThrough() {
        var p = fixtures().projectWithLeader();
        long tokenId = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        fixtures().revokeToken(tokenId);

        HttpResponse<String> res = client().get("/mcp/test-probe/projects/" + p.projectId(), "Authorization",
                bearer(p.leaderAccountId(), p.projectId(), tokenId));
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"token_revoked\"");
    }

    @Test
    void withdrawnAccountsSession_is401_evenWithActiveMemberRow() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient bobClient = loginAs(bob);

        fixtures().withdraw(bob);

        // 탈퇴로 세션이 무효가 되었으니 404(존재 숨김)가 아니라 401 — /api/me 와 같은 판정
        HttpResponse<String> res = bobClient.get("/api/test-probe/projects/" + p.projectId());
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"unauthenticated\"").contains(AccessErrors.UNAUTHENTICATED_MESSAGE);
    }

    @Test
    void agentToken_onWebApi_is401() {
        var p = fixtures().projectWithLeader();
        long tokenId = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        String token = bearer(p.leaderAccountId(), p.projectId(), tokenId);

        HttpResponse<String> res = client().get("/api/test-probe/projects/" + p.projectId(), "Authorization", token);
        assertThat(res.statusCode()).isEqualTo(401);
    }
}
