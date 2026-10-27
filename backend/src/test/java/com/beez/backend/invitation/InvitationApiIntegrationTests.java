package com.beez.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * MEM-A01 ~ A05 (S15P31A108-174). Jira 완료 조건 "만료·잘못된 초대 거부", 1회 사용, -173 에서 넘어온
 * "삭제된 프로젝트 초대 수락 차단"을 실제 DB·필터 체인 위에서 확인한다.
 */
class InvitationApiIntegrationTests extends IntegrationTestSupport {

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** 깨지면: 초대 링크로 팀에 들어올 수 없거나, 링크 하나로 여러 명이 들어온다 (MEM-A01·A04·A05, 1회 사용). */
    @Test
    void invite_preview_accept_once() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        HttpResponse<String> created = leader.post(invitations(p.projectId()), "{}");
        assertThat(created.statusCode()).isEqualTo(201);
        String token = tokenOf(created);

        JsonNode preview = read(client().post("/api/invitations/preview", tokenBody(token)));
        assertThat(preview.get("project_name").asString()).isEqualTo("fixture-project");
        assertThat(preview.get("login_required").asBoolean()).isTrue();

        ApiClient bob = loginAsNewAccount();
        assertThat(read(bob.post("/api/invitations/preview", tokenBody(token))).get("login_required").asBoolean())
                .isFalse();
        HttpResponse<String> accepted = bob.post("/api/invitations/accept", tokenBody(token));
        assertThat(accepted.statusCode()).isEqualTo(200);
        JsonNode body = read(accepted);
        assertThat(body.get("project").get("my_role").asString()).isEqualTo("MEMBER");
        assertThat(body.get("member").get("role").asString()).isEqualTo("MEMBER");
        assertThat(body.get("member").get("agent_connected").asBoolean()).isFalse();
        assertThat(bob.get("/api/projects/" + p.projectId()).statusCode()).isEqualTo(200);

        HttpResponse<String> again = loginAsNewAccount().post("/api/invitations/accept", tokenBody(token));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("invitation_used");
        assertThat(client().post("/api/invitations/preview", tokenBody(token)).statusCode()).isEqualTo(404);

        // 목록에는 수락 시각이 보이고 토큰은 어디에도 없다 (MEM-A01 처리 규칙)
        HttpResponse<String> list = leader.get(invitations(p.projectId()));
        assertThat(list.body()).doesNotContain(token);
        assertThat(read(list).get("items").get(0).has("accepted_at")).isTrue();
    }

    /** 깨지면: 잘못된·만료된·폐기된 링크로 팀에 들어올 수 있다 (Jira 완료 조건 1, MEM-A03). */
    @Test
    void invalid_expired_revoked_areRejected() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        ApiClient bob = loginAsNewAccount();

        assertThat(client().post("/api/invitations/preview", tokenBody("no-such-token")).statusCode()).isEqualTo(404);
        assertThat(bob.post("/api/invitations/accept", tokenBody("no-such-token")).statusCode()).isEqualTo(404);
        assertThat(bob.post("/api/invitations/accept", "{\"token\":\" \"}").statusCode()).isEqualTo(400);

        HttpResponse<String> expiring = leader.post(invitations(p.projectId()), "{}");
        jdbc.update("UPDATE invitation SET expires_at = now() - interval '1 second' WHERE id = ?",
                Long.parseLong(read(expiring).get("invitation").get("id").asString()));
        HttpResponse<String> expired = bob.post("/api/invitations/accept", tokenBody(tokenOf(expiring)));
        assertThat(expired.statusCode()).isEqualTo(410);
        assertThat(expired.body()).contains("invitation_expired");
        assertThat(client().post("/api/invitations/preview", tokenBody(tokenOf(expiring))).statusCode()).isEqualTo(410);

        HttpResponse<String> revoking = leader.post(invitations(p.projectId()), "{}");
        String revokePath = invitations(p.projectId()) + "/" + read(revoking).get("invitation").get("id").asString();
        assertThat(leader.delete(revokePath, "").statusCode()).isEqualTo(204);
        assertThat(leader.delete(revokePath, "").statusCode()).isEqualTo(204);
        assertThat(client().post("/api/invitations/preview", tokenBody(tokenOf(revoking))).statusCode()).isEqualTo(404);
        assertThat(bob.post("/api/invitations/accept", tokenBody(tokenOf(revoking))).statusCode()).isEqualTo(404);
        assertThat(bob.get("/api/projects/" + p.projectId()).statusCode()).isEqualTo(404);
    }

    /** 깨지면: 팀원·외부인이 초대를 만들거나 보고 지울 수 있다, 또는 다른 프로젝트 초대를 지울 수 있다 (MEM-A01~A03 LEADER). */
    @Test
    void manageInvitations_leaderOnly() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        ApiClient member = client().login(fixtures().emailOf(bob), AccessFixtures.PASSWORD);
        ApiClient outsider = loginAsNewAccount();
        String path = invitations(p.projectId());
        String invitationId = read(leader.post(path, "{}")).get("invitation").get("id").asString();

        assertThat(member.post(path, "{}").statusCode()).isEqualTo(403);
        assertThat(member.get(path).statusCode()).isEqualTo(403);
        assertThat(member.delete(path + "/" + invitationId, "").statusCode()).isEqualTo(403);
        assertThat(outsider.post(path, "{}").statusCode()).isEqualTo(404);
        assertThat(client().post(path, "{}").statusCode()).isEqualTo(401);

        var other = fixtures().projectWithLeader();
        ApiClient otherLeader = client().login(other.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(otherLeader.delete(invitations(other.projectId()) + "/" + invitationId, "").statusCode())
                .isEqualTo(404);
    }

    /**
     * 깨지면: 이메일을 지정한 초대를 다른 계정이 쓰거나, 이미 팀원인 사람이 초대를 소비한다
     * (MEM-A01 already_member, MEM-A05 Q05 제안 — 이메일 일치, 대소문자 무시).
     */
    @Test
    void emailInvitation_onlyForThatAccount_andNotForMembers() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String carolEmail = AccessFixtures.uniqueEmail();
        fixtures().account(carolEmail);

        assertThat(leader.post(invitations(p.projectId()), "{\"email\":\"not-an-email\"}").statusCode()).isEqualTo(400);
        HttpResponse<String> forLeader = leader.post(invitations(p.projectId()),
                "{\"email\":\"" + p.leaderEmail().toUpperCase() + "\"}");
        assertThat(forLeader.statusCode()).isEqualTo(409);
        assertThat(forLeader.body()).contains("already_member");

        HttpResponse<String> created = leader.post(invitations(p.projectId()),
                "{\"email\":\" " + carolEmail.toUpperCase() + " \"}");
        assertThat(read(created).get("invitation").get("invited_email").asString()).isEqualTo(carolEmail);
        String token = tokenOf(created);
        assertThat(loginAsNewAccount().post("/api/invitations/accept", tokenBody(token)).statusCode()).isEqualTo(404);

        // 팀원이 링크를 눌러도 초대가 소비되지 않는다
        HttpResponse<String> open = leader.post(invitations(p.projectId()), "{}");
        HttpResponse<String> byMember = leader.post("/api/invitations/accept", tokenBody(tokenOf(open)));
        assertThat(byMember.statusCode()).isEqualTo(409);
        assertThat(byMember.body()).contains("already_member");

        ApiClient carol = client().login(carolEmail, AccessFixtures.PASSWORD);
        assertThat(carol.post("/api/invitations/accept", tokenBody(token)).statusCode()).isEqualTo(200);
        assertThat(carol.post("/api/invitations/accept", tokenBody(tokenOf(open))).statusCode()).isEqualTo(409);
    }

    /** 깨지면: 삭제한 프로젝트에 초대로 들어가 데이터가 다시 보인다 (-173 PR #29 에서 넘어온 약속). */
    @Test
    void archivedProject_invitationIsInvalid() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String token = tokenOf(leader.post(invitations(p.projectId()), "{}"));
        fixtures().archiveProject(p.projectId());

        assertThat(client().post("/api/invitations/preview", tokenBody(token)).statusCode()).isEqualTo(404);
        ApiClient bob = loginAsNewAccount();
        assertThat(bob.post("/api/invitations/accept", tokenBody(token)).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ?", Integer.class,
                p.projectId())).isEqualTo(1);
    }

    /** 깨지면: 나갔던 팀원이 다시 초대받으면 UNIQUE(project_id, account_id) 로 500 이 나거나 예전 표시 정보가 남는다. */
    @Test
    void removedMember_rejoinsSameRow_asMember() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        long bobMember = fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        jdbc.update("UPDATE project_member SET area = 'backend', color = '#FFD54F' WHERE id = ?", bobMember);
        fixtures().removeMember(bobMember);
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String token = tokenOf(leader.post(invitations(p.projectId()), "{}"));

        JsonNode member = read(client().login(fixtures().emailOf(bob), AccessFixtures.PASSWORD)
                .post("/api/invitations/accept", tokenBody(token))).get("member");
        assertThat(member.get("id").asString()).isEqualTo(String.valueOf(bobMember));
        assertThat(member.has("domain")).isFalse();
        assertThat(member.has("color")).isFalse();
        assertThat(jdbc.queryForObject("SELECT active AND left_at IS NULL FROM project_member WHERE id = ?",
                Boolean.class, bobMember)).isTrue();
    }

    /** 깨지면: 같은 링크를 동시에 누르면 둘 다 들어온다 — 순서대로 누를 때만 1회 사용이 지켜진다 (행 잠금). */
    @Test
    void concurrentAccept_onlyOneWins() throws Exception {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String token = tokenOf(leader.post(invitations(p.projectId()), "{}"));
        List<ApiClient> users = List.of(loginAsNewAccount(), loginAsNewAccount(), loginAsNewAccount());

        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> calls = users.stream()
                .map(u -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return u.post("/api/invitations/accept", tokenBody(token)).statusCode();
                }))
                .toList();
        start.countDown();
        List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ?", Integer.class,
                p.projectId())).isEqualTo(2);
    }

    private static String invitations(long projectId) {
        return "/api/projects/" + projectId + "/invitations";
    }

    private String tokenOf(HttpResponse<String> created) {
        String url = read(created).get("invite_url").asString();
        return url.substring(url.indexOf("token=") + "token=".length());
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private ApiClient loginAsNewAccount() {
        long accountId = fixtures().account();
        return client().login(fixtures().emailOf(accountId), AccessFixtures.PASSWORD);
    }

    private JsonNode read(HttpResponse<String> res) {
        return json.readTree(res.body());
    }
}
