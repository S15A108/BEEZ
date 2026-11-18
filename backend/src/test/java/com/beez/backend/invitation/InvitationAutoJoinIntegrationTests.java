package com.beez.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beez.backend.common.mail.MailMessage;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.RecordingMailGateway;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 이메일 초대·초대 자동 참여·다시 보내기 (S15P31A108-220, Q05). Jira 완료 조건 중
 * "초대 응답으로 가입 여부를 구분할 수 없음", "로그인 시 대기 초대 전환, 스스로 나간 사람은 전환 안 됨"을 실제 DB 위에서 본다.
 * (무효 초대 404·동시 수락은 {@link InvitationApiIntegrationTests}.)
 * 초대 메일은 비동기로 나가므로 RecordingMailGateway 에 도착할 때까지 기다린다.
 */
class InvitationAutoJoinIntegrationTests extends IntegrationTestSupport {

    private static final Pattern INVITE_TOKEN = Pattern.compile("\\?invite=([A-Za-z0-9_-]+)");

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RecordingMailGateway mail;

    /**
     * 깨지면: 팀장이 아무 이메일이나 넣어 보고 가입 여부를 알아낼 수 있다 (Q05 3) — 가입한 주소와 안 한 주소의 결과 모양이 같아야 한다.
     * 메일이 프로젝트 주소(/projects/{id}?invite=)로 가지 않아도 깨진다.
     */
    @Test
    void emailInvite_resultsDoNotRevealRegistration_andMailLinksToProject() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String registered = AccessFixtures.uniqueEmail();
        fixtures().account(registered);
        String unregistered = AccessFixtures.uniqueEmail();

        HttpResponse<String> res = leader.post(invitations(p.projectId()), "{\"emails\":[\"" + registered.toUpperCase()
                + "\",\" " + unregistered + "\",\"" + p.leaderEmail() + "\",\"" + registered + "\"],\"message\":\"같이 해요\"}");
        assertThat(res.statusCode()).isEqualTo(201);
        JsonNode body = read(res);
        assertThat(body.has("invitation")).isFalse();
        assertThat(body.has("invite_url")).isFalse();   // 토큰은 메일에만
        JsonNode results = body.get("results");
        assertThat(results).hasSize(3);   // 대소문자만 다른 주소는 하나로
        assertThat(results.get(0).get("email").asString()).isEqualTo(registered);
        assertThat(results.get(0).get("status").asString()).isEqualTo("INVITED");
        assertThat(results.get(1).get("status").asString()).isEqualTo("INVITED");
        assertThat(fieldNames(results.get(0))).isEqualTo(fieldNames(results.get(1)));
        assertThat(results.get(2).get("status").asString()).isEqualTo("ALREADY_MEMBER");

        // 대기 초대가 있는 주소를 다시 초대하면 새로 만들지 않는다
        JsonNode again = read(leader.post(invitations(p.projectId()), "{\"emails\":[\"" + unregistered + "\"]}"));
        assertThat(again.get("results").get(0).get("status").asString()).isEqualTo("ALREADY_INVITED");
        assertThat(again.get("results").get(0).get("invitation_id").asString())
                .isEqualTo(results.get(1).get("invitation_id").asString());

        awaitMail(unregistered);   // 시험이 끝날 때 보내는 중인 메일을 남기지 않는다 (다른 시험의 실패 예약을 가져가지 않게)
        MailMessage m = awaitMail(registered);
        assertThat(m.text()).contains("/projects/" + p.projectId() + "?invite=").contains("같이 해요");
        assertThat(client().post("/api/invitations/preview", tokenBody(tokenIn(m))).statusCode()).isEqualTo(200);
        assertThat(mail.countTo(registered)).isEqualTo(1);
    }

    /** 깨지면: 요청 모양 실수(email 과 emails 를 같이, 한도 초과, 메일 없는 message)가 조용히 일부만 처리된다. */
    @Test
    void emailInvite_rejectsAmbiguousRequests() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String path = invitations(p.projectId());
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= CreateInvitationRequest.MAX_EMAILS; i++) {
            many.add("\"" + AccessFixtures.uniqueEmail() + "\"");
        }

        assertThat(leader.post(path, "{\"email\":\"a@example.com\",\"emails\":[\"b@example.com\"]}").statusCode())
                .isEqualTo(400);
        assertThat(leader.post(path, "{\"emails\":[]}").statusCode()).isEqualTo(400);
        assertThat(leader.post(path, "{\"emails\":[\"not-an-email\"]}").statusCode()).isEqualTo(400);
        assertThat(leader.post(path, "{\"emails\":[" + String.join(",", many) + "]}").statusCode()).isEqualTo(400);
        assertThat(leader.post(path, "{\"message\":\"hi\"}").statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invitation WHERE project_id = ?", Integer.class,
                p.projectId())).isZero();
    }

    /**
     * 깨지면: 로그인 자동 참여 규칙(ACC-A02, Q05 1·4, MEM-A03)이 어긋난다 — 대기 초대가 멤버가 되지 않거나(대소문자 무시 포함),
     * 취소한 초대로 들어오거나, 스스로 나간 사람이 로그인만으로 다시 들어오거나, 제외된 사람이 되살아나지 않는다.
     */
    @Test
    void login_convertsPendingInvitations_byEndReason() {
        String email = AccessFixtures.uniqueEmail();
        long me = fixtures().account(email);
        var pending = fixtures().projectWithLeader();
        var revoked = fixtures().projectWithLeader();
        var removed = fixtures().projectWithLeader();
        var left = fixtures().projectWithLeader();
        long removedMember = fixtures().member(removed.projectId(), me, ProjectRole.MEMBER);
        long leftMember = fixtures().member(left.projectId(), me, ProjectRole.MEMBER);
        endMembership(removedMember, "REMOVED");
        endMembership(leftMember, "LEFT");

        inviteByEmail(pending, email.toUpperCase());
        String revokedId = inviteByEmail(revoked, email);
        inviteByEmail(removed, email);
        String leftId = inviteByEmail(left, email);
        assertThat(client().login(revoked.leaderEmail(), AccessFixtures.PASSWORD)
                .delete(invitations(revoked.projectId()) + "/" + revokedId, "").statusCode()).isEqualTo(204);
        String leftToken = tokenIn(awaitMail(email, left.projectId()));
        awaitMail(email, pending.projectId());
        awaitMail(email, revoked.projectId());
        awaitMail(email, removed.projectId());

        ApiClient session = client().login(email, AccessFixtures.PASSWORD);

        assertThat(session.get("/api/projects/" + pending.projectId()).statusCode()).isEqualTo(200);
        assertThat(session.get("/api/projects/" + revoked.projectId()).statusCode()).isEqualTo(404);
        assertThat(session.get("/api/projects/" + removed.projectId()).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT role FROM project_member WHERE id = ?", String.class, removedMember))
                .isEqualTo("MEMBER");
        assertThat(session.get("/api/projects/" + left.projectId()).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT accepted_at IS NULL FROM invitation WHERE id = ?", Boolean.class,
                Long.parseLong(leftId))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM invitation WHERE email = ? AND accepted_at IS NOT NULL AND accepted_account_id = ?",
                Integer.class, email, me)).isEqualTo(2);

        // 스스로 나간 사람은 메일 링크를 직접 열어(MEM-A05) 같은 멤버십 행으로 돌아온다
        HttpResponse<String> accepted = session.post("/api/invitations/accept", tokenBody(leftToken));
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(read(accepted).get("member").get("id").asString()).isEqualTo(String.valueOf(leftMember));
    }

    /**
     * 깨지면: 로그아웃 상태로 메일 링크를 열고 → 로그인(자동 참여) → 링크로 돌아온 화면이 '페이지를 찾을 수 없어요'가 된다 (MEM-A04 Q05),
     * 또는 다른 계정으로 로그인한 화면이 남의 초대를 자기 것으로 보고 바로 수락을 부른다 (account_matches).
     */
    @Test
    void preview_afterAutoJoin_isStillValid_andTellsWhetherAccountMatches() {
        var p = fixtures().projectWithLeader();
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        inviteByEmail(p, email);
        String token = tokenIn(awaitMail(email, p.projectId()));

        JsonNode loggedOut = read(client().post("/api/invitations/preview", tokenBody(token)));
        assertThat(loggedOut.get("login_required").asBoolean()).isTrue();
        assertThat(loggedOut.has("account_matches")).isFalse();
        assertThat(loggedOut.get("invited_email").asString()).isEqualTo(email);
        assertThat(loggedOut.get("inviter").get("email").asString()).isEqualTo(p.leaderEmail());
        assertThat(loggedOut.has("expires_at")).isFalse();

        ApiClient invitee = client().login(email, AccessFixtures.PASSWORD);   // 여기서 자동 참여
        HttpResponse<String> back = invitee.post("/api/invitations/preview", tokenBody(token));
        assertThat(back.statusCode()).isEqualTo(200);
        assertThat(read(back).get("account_matches").asBoolean()).isTrue();

        long other = fixtures().account();
        JsonNode stranger = read(client().login(fixtures().emailOf(other), AccessFixtures.PASSWORD)
                .post("/api/invitations/preview", tokenBody(token)));
        assertThat(stranger.get("account_matches").asBoolean()).isFalse();
    }

    /** 깨지면: 초대받은 이메일로 막 가입한 사람이 프로젝트에 들어오지 않는다 (ACC-A01 자동 참여). */
    @Test
    void register_convertsPendingInvitation() {
        var p = fixtures().projectWithLeader();
        String email = AccessFixtures.uniqueEmail();
        inviteByEmail(p, email);
        awaitMail(email, p.projectId());

        HttpResponse<String> registered = client().post("/api/auth/register", "{\"email\":\"" + email
                + "\",\"password\":\"" + AccessFixtures.PASSWORD + "\",\"name\":\"새팀원\",\"verified_token\":\""
                + fixtures().verifiedToken(email) + "\"}");
        assertThat(registered.statusCode()).isEqualTo(201);
        long accountId = Long.parseLong(read(registered).get("id").asString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND account_id = ?"
                + " AND active", Integer.class, p.projectId(), accountId)).isEqualTo(1);
    }

    /**
     * 깨지면: 팀장이 제외한 사람이 제외 전에 남아 있던 대기 초대로 다음 로그인(자동 참여)이나 예전 메일 링크(MEM-A05)로 바로 돌아온다.
     * (링크 초대로 먼저 들어와 이메일 초대가 대기로 남은 경우.) 제외 뒤 새로 초대하면 로그인으로 되살아나야 한다 (Q05 4).
     */
    @Test
    void removal_revokesStalePendingInvitation_butReinviteRevives() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        long me = fixtures().account(email);
        ApiClient session = client().login(email, AccessFixtures.PASSWORD);   // 초대 전에 로그인해 둔 세션

        inviteByEmail(p, email);
        String staleToken = tokenIn(awaitMail(email, p.projectId()));
        String linkToken = read(leader.post(invitations(p.projectId()), "{}")).get("invite_url").asString()
                .replaceAll(".*token=", "");
        String memberId = read(session.post("/api/invitations/accept", tokenBody(linkToken)))
                .get("member").get("id").asString();
        assertThat(leader.post("/api/projects/" + p.projectId() + "/members/" + memberId + "/remove", "{}")
                .statusCode()).isEqualTo(204);

        ApiClient again = client().login(email, AccessFixtures.PASSWORD);
        assertThat(again.get("/api/projects/" + p.projectId()).statusCode()).isEqualTo(404);
        assertThat(again.post("/api/invitations/accept", tokenBody(staleToken)).statusCode()).isEqualTo(404);

        inviteByEmail(p, email);
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(email) == 2);   // 새 초대 메일까지 다 나간 뒤
        assertThat(client().login(email, AccessFixtures.PASSWORD).get("/api/projects/" + p.projectId()).statusCode())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM project_member WHERE project_id = ? AND account_id = ?",
                Integer.class, p.projectId(), me)).isEqualTo(1);   // 같은 멤버십 행을 되살림
    }

    /** 깨지면: 스스로 나간 사람이 나가기 전에 받은 메일 링크로 다시 초대받지 않고 돌아온다 (Q05 4 — 다시 초대받은 링크로만). */
    @Test
    void leave_revokesStalePendingInvitation() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        ApiClient session = client().login(email, AccessFixtures.PASSWORD);

        inviteByEmail(p, email);
        String staleToken = tokenIn(awaitMail(email, p.projectId()));
        String linkToken = read(leader.post(invitations(p.projectId()), "{}")).get("invite_url").asString()
                .replaceAll(".*token=", "");
        assertThat(session.post("/api/invitations/accept", tokenBody(linkToken)).statusCode()).isEqualTo(200);
        assertThat(session.post("/api/projects/" + p.projectId() + "/leave", "{}").statusCode()).isEqualTo(204);

        assertThat(session.post("/api/invitations/accept", tokenBody(staleToken)).statusCode()).isEqualTo(404);

        inviteByEmail(p, email);
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(email) == 2);
        assertThat(session.post("/api/invitations/accept", tokenBody(tokenIn(mail.lastTo(email)))).statusCode())
                .isEqualTo(200);
    }

    /**
     * 깨지면: 자동 참여로 들어왔다가 제외된 사람이 예전 메일 링크를 열면 미리보기는 통과(account_matches=true)하는데 수락은 404 라,
     * 화면이 '바로 참여'를 시도했다가 실패한다. 미리보기도 수락과 같은 기준으로 404 여야 한다.
     */
    @Test
    void preview_ofAcceptedInvitation_isNotFoundAfterRemoval() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        long me = fixtures().account(email);
        inviteByEmail(p, email);
        String token = tokenIn(awaitMail(email, p.projectId()));
        ApiClient session = client().login(email, AccessFixtures.PASSWORD);   // 자동 참여
        assertThat(session.post("/api/invitations/preview", tokenBody(token)).statusCode()).isEqualTo(200);

        Long memberId = jdbc.queryForObject("SELECT id FROM project_member WHERE project_id = ? AND account_id = ?",
                Long.class, p.projectId(), me);
        assertThat(leader.post("/api/projects/" + p.projectId() + "/members/" + memberId + "/remove", "{}")
                .statusCode()).isEqualTo(204);

        assertThat(session.post("/api/invitations/preview", tokenBody(token)).statusCode()).isEqualTo(404);
        assertThat(session.post("/api/invitations/accept", tokenBody(token)).statusCode()).isEqualTo(404);
    }

    /**
     * 깨지면: 다시 보내기(MEM-A11) 뒤에도 이전 링크가 살아 있거나, 메일이 실패했는데 이전 링크까지 죽어 초대받은 사람이 쓸 링크가 없다.
     * 수락·폐기된 초대를 다시 보내 이미 끝난 초대에 새 링크가 생겨도 깨진다.
     */
    @Test
    void resend_rotatesToken_andKeepsOldLinkWhenMailFails() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        String id = inviteByEmail(p, email);
        String first = tokenIn(awaitMail(email, p.projectId()));
        String resend = invitations(p.projectId()) + "/" + id + "/resend";

        mail.failNextTo(email);
        HttpResponse<String> failed = leader.post(resend, null);
        assertThat(failed.statusCode()).isEqualTo(503);
        assertThat(failed.body()).contains("mail_send_failed");
        assertThat(client().post("/api/invitations/preview", tokenBody(first)).statusCode()).isEqualTo(200);

        HttpResponse<String> ok = leader.post(resend, null);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(read(ok).get("invitation").get("id").asString()).isEqualTo(id);
        assertThat(ok.body()).doesNotContain("invite=");
        String second = tokenIn(mail.lastTo(email));
        assertThat(second).isNotEqualTo(first);
        assertThat(client().post("/api/invitations/preview", tokenBody(first)).statusCode()).isEqualTo(404);
        assertThat(client().post("/api/invitations/preview", tokenBody(second)).statusCode()).isEqualTo(200);

        assertThat(leader.delete(invitations(p.projectId()) + "/" + id, "").statusCode()).isEqualTo(204);
        HttpResponse<String> revoked = leader.post(resend, null);
        assertThat(revoked.statusCode()).isEqualTo(409);
        assertThat(revoked.body()).contains("invitation_not_pending");

        String linkOnly = read(leader.post(invitations(p.projectId()), "{}")).get("invitation").get("id").asString();
        assertThat(leader.post(invitations(p.projectId()) + "/" + linkOnly + "/resend", null).statusCode())
                .isEqualTo(404);
    }

    /** 깨지면: SMTP 가 실패하면(-272 전 dev 서버처럼) 초대가 사라져, 같은 이메일로 로그인해도 들어오지 못한다. */
    @Test
    void createMailFailure_keepsInvitation() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);

        mail.failNextTo(email);
        JsonNode res = read(leader.post(invitations(p.projectId()), "{\"emails\":[\"" + email + "\"]}"));
        assertThat(res.get("results").get(0).get("status").asString()).isEqualTo("INVITED");
        // 비동기 발송이 실패 예약을 쓸 때까지 기다린다 (남은 예약이 뒤 시험에 새지 않게)
        await().atMost(Duration.ofSeconds(10)).until(() -> !mail.failPendingTo(email));
        assertThat(mail.countTo(email)).isZero();

        assertThat(client().login(email, AccessFixtures.PASSWORD).get("/api/projects/" + p.projectId()).statusCode())
                .isEqualTo(200);
    }

    // ---- helpers ----

    private String inviteByEmail(AccessFixtures.ProjectWithLeader p, String email) {
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        HttpResponse<String> res = leader.post(invitations(p.projectId()), "{\"emails\":[\"" + email + "\"]}");
        assertThat(res.statusCode()).isEqualTo(201);
        JsonNode result = read(res).get("results").get(0);
        assertThat(result.get("status").asString()).isEqualTo("INVITED");
        return result.get("invitation_id").asString();
    }

    private void endMembership(long memberId, String reason) {
        jdbc.update("UPDATE project_member SET active = false, left_at = now(), left_reason = ? WHERE id = ?",
                reason, memberId);
    }

    private MailMessage awaitMail(String email) {
        return await().atMost(Duration.ofSeconds(10)).until(() -> mail.lastTo(email), m -> m != null);
    }

    /** 이 프로젝트 초대 메일 (한 주소로 여러 프로젝트 초대가 갈 때). */
    private MailMessage awaitMail(String email, long projectId) {
        String marker = "/projects/" + projectId + "?invite=";
        return await().atMost(Duration.ofSeconds(10))
                .until(() -> mail.lastTo(email, marker), m -> m != null);
    }

    private static String tokenIn(MailMessage m) {
        Matcher matcher = INVITE_TOKEN.matcher(m.text());
        assertThat(matcher.find()).as("초대 메일에 링크가 없음").isTrue();
        return matcher.group(1);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>(node.propertyNames());
        names.sort(null);
        return names;
    }

    private static String invitations(long projectId) {
        return "/api/projects/" + projectId + "/invitations";
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private JsonNode read(HttpResponse<String> res) {
        return json.readTree(res.body());
    }
}
