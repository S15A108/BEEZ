package com.beez.backend.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** ACC-A05 프로필 수정, ACC-A06 비밀번호 변경, ACC-A10·A13 회원 탈퇴 (S15P31A108-171). */
class AccountSettingsIntegrationTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate db;

    @Autowired
    ObjectMapper json;

    private static final String NEW_PASSWORD = "changed-pass-2";

    private HttpResponse<String> changePassword(ApiClient user, String current, String next) {
        return user.send(user.request("/api/me/password")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(
                        "{\"current_password\":\"" + current + "\",\"new_password\":\"" + next + "\"}")));
    }

    private static HttpResponse<String> withdraw(ApiClient user, String password) {
        return user.post("/api/me/withdraw", "{\"password\":\"" + password + "\"}");
    }

    private HttpResponse<String> login(String email, String password) {
        return client().post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    /** 깨지면: 프로필 수정이 저장되지 않거나, 이름을 비워 기록의 작성자 이름이 사라지거나, 빈 요청이 그대로 통과한다 (ACC-A05). */
    @Test
    void updateProfile_changesOnlySentFields_andRejectsBlankName() {
        long id = fixtures().account();
        ApiClient user = client().login(fixtures().emailOf(id), AccessFixtures.PASSWORD);

        HttpResponse<String> both = user.patch("/api/me", "{\"name\":\"  새이름 \",\"avatar\":\"bee-yellow\"}");
        assertThat(both.statusCode()).as(both.body()).isEqualTo(200);
        assertThat(both.body()).contains("\"name\":\"새이름\"").contains("\"avatar\":\"bee-yellow\"");

        HttpResponse<String> clearAvatar = user.patch("/api/me", "{\"avatar\":\"\"}");
        assertThat(clearAvatar.body()).contains("\"name\":\"새이름\"").doesNotContain("\"avatar\"");

        assertThat(user.patch("/api/me", "{\"name\":\"   \"}").body()).contains("\"code\":\"validation_failed\"");
        assertThat(user.patch("/api/me", "{}").statusCode()).isEqualTo(400);
        assertThat(user.get("/api/me").body()).contains("\"name\":\"새이름\"");
    }

    /**
     * 깨지면: 현재 비밀번호를 모르는 사람(열린 세션을 주운 사람)이 비밀번호를 바꾸거나, 바꾼 뒤에도 다른 곳의 세션이 살아 있어
     * 비밀번호를 바꾼 의미가 없어진다 (ACC-A06, 06-project-access §세션 "-171 비밀번호 변경").
     */
    @Test
    void changePassword_requiresCurrent_thenEndsOtherSessionsButKeepsThisOne() {
        long id = fixtures().account();
        String email = fixtures().emailOf(id);
        ApiClient here = client().login(email, AccessFixtures.PASSWORD);
        ApiClient elsewhere = client().login(email, AccessFixtures.PASSWORD);

        HttpResponse<String> wrong = changePassword(here, "not-my-pass-9", NEW_PASSWORD);
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("\"code\":\"current_password_mismatch\"");
        assertThat(changePassword(here, AccessFixtures.PASSWORD, "short1").body())
                .as("정책 위반은 가입과 같은 오류").contains("\"code\":\"validation_failed\"");
        assertThat(elsewhere.get("/api/me").statusCode()).as("실패한 변경은 세션을 건드리지 않는다").isEqualTo(200);

        assertThat(changePassword(here, AccessFixtures.PASSWORD, NEW_PASSWORD).statusCode()).isEqualTo(204);

        assertThat(here.get("/api/me").statusCode()).as("바꾼 세션은 유지").isEqualTo(200);
        assertThat(elsewhere.get("/api/me").statusCode()).as("다른 세션은 끊김").isEqualTo(401);
        assertThat(login(email, AccessFixtures.PASSWORD).statusCode()).isEqualTo(401);
        assertThat(login(email, NEW_PASSWORD).statusCode()).isEqualTo(200);
    }

    /**
     * 깨지면: 팀원이 남은 프로젝트의 팀장이 탈퇴해 팀장 없는 프로젝트가 생기거나(Jira 완료 조건 "팀장 위임 전 탈퇴 거부"),
     * 위임한 뒤에도 탈퇴가 막히거나, 화면이 막힌 프로젝트를 보여 줄 수 없다 (ACC-A10 409 details, ACC-A13).
     */
    @Test
    void leaderOfTeamProject_isBlockedUntilLeadershipTransfer() {
        var p = fixtures().projectWithLeader();
        long mate = fixtures().account();
        long mateMemberId = fixtures().member(p.projectId(), mate, ProjectRole.MEMBER);
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        JsonNode check = json.readTree(leader.get("/api/me/withdrawal-check").body());
        assertThat(check.get("can_withdraw").asBoolean()).isFalse();
        JsonNode blockedInCheck = check.get("blocked_projects").get(0);
        assertThat(blockedInCheck.get("project_id").asString()).isEqualTo(String.valueOf(p.projectId()));
        assertThat(blockedInCheck.get("my_role").asString()).isEqualTo("LEADER");
        assertThat(blockedInCheck.get("member_count").asInt()).isEqualTo(1);

        HttpResponse<String> blocked = withdraw(leader, AccessFixtures.PASSWORD);
        assertThat(blocked.statusCode()).isEqualTo(409);
        JsonNode error = json.readTree(blocked.body()).get("error");
        assertThat(error.get("code").asString()).isEqualTo("leader_of_team_projects");
        assertThat(error.get("details").get("blocked_projects").get(0).get("project_id").asString())
                .isEqualTo(String.valueOf(p.projectId()));
        assertThat(db.queryForObject("SELECT deleted_at IS NULL FROM account WHERE id = ?", Boolean.class,
                p.leaderAccountId())).as("막히면 아무것도 바꾸지 않는다").isTrue();

        assertThat(leader.post("/api/projects/" + p.projectId() + "/leadership-transfer",
                "{\"target_member_id\":\"" + mateMemberId + "\"}").statusCode()).isEqualTo(200);
        assertThat(json.readTree(leader.get("/api/me/withdrawal-check").body()).get("can_withdraw").asBoolean())
                .isTrue();
        assertThat(withdraw(leader, AccessFixtures.PASSWORD).statusCode()).isEqualTo(204);
        assertThat(db.queryForObject("SELECT archived_at IS NULL FROM project WHERE id = ?", Boolean.class,
                p.projectId())).as("팀 프로젝트는 남는다").isTrue();
    }

    /**
     * 깨지면: 탈퇴한 사람의 에이전트 토큰·세션이 살아 있거나, 이메일·이름이 DB 에 남거나, 맡았던 작업·멤버십 기록이 사라지거나,
     * 같은 이메일로 다시 가입할 수 없다 (Jira -171 작업 5, ACC-A10 처리 규칙).
     */
    @Test
    void withdraw_revokesTokensAndSessions_erasesPersonalData_keepsRecords_andFreesEmail() {
        // 혼자 쓰는 프로젝트의 팀장이면서 다른 팀 프로젝트의 팀원
        var solo = fixtures().projectWithLeader();
        long me = solo.leaderAccountId();
        String email = solo.leaderEmail();
        var team = fixtures().projectWithLeader();
        long myMemberId = fixtures().member(team.projectId(), me, ProjectRole.MEMBER);
        long soloToken = fixtures().agentToken(solo.projectId(), me);
        long teamToken = fixtures().agentToken(team.projectId(), me);
        long taskId = db.queryForObject("""
                INSERT INTO task (project_id, key, title, assignee_member_id, status)
                VALUES (?, 'T-1', '맡은 작업', ?, 'DRAFT') RETURNING id
                """, Long.class, team.projectId(), myMemberId);
        ApiClient here = client().login(email, AccessFixtures.PASSWORD);
        ApiClient elsewhere = client().login(email, AccessFixtures.PASSWORD);

        HttpResponse<String> wrong = withdraw(here, "not-my-pass-9");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(wrong.body()).contains("\"code\":\"invalid_credentials\"");
        assertThat(here.get("/api/me").statusCode()).as("비밀번호가 틀려도 로그아웃되지 않는다").isEqualTo(200);

        assertThat(withdraw(here, AccessFixtures.PASSWORD).statusCode()).isEqualTo(204);

        // 세션: 이 브라우저와 다른 브라우저 모두
        assertThat(here.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(elsewhere.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(db.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name = ?", Integer.class,
                Long.toString(me))).isZero();
        // 토큰: 모든 프로젝트
        assertThat(db.queryForObject("SELECT count(*) FROM agent_token WHERE id IN (?, ?) AND revoked_at IS NOT NULL",
                Integer.class, soloToken, teamToken)).isEqualTo(2);
        // 개인정보 삭제, 행은 남는다
        var row = db.queryForMap("SELECT email, display_name, password_hash, deleted_at FROM account WHERE id = ?", me);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("password_hash")).isNull();
        assertThat(row.get("display_name")).isEqualTo("탈퇴한 사용자");
        assertThat(row.get("deleted_at")).isNotNull();
        // 프로젝트: 혼자 쓰던 것은 삭제, 팀 프로젝트는 멤버십만 끝나고 작업 기록은 그대로
        assertThat(db.queryForObject("SELECT archived_at IS NOT NULL FROM project WHERE id = ?", Boolean.class,
                solo.projectId())).isTrue();
        assertThat(db.queryForObject("SELECT archived_at IS NULL FROM project WHERE id = ?", Boolean.class,
                team.projectId())).isTrue();
        assertThat(db.queryForObject("SELECT active FROM project_member WHERE id = ?", Boolean.class, myMemberId))
                .isFalse();
        assertThat(db.queryForObject("SELECT assignee_member_id FROM task WHERE id = ?", Long.class, taskId))
                .isEqualTo(myMemberId);
        // 다시 로그인할 수 없고, 같은 이메일로 새 계정을 만들 수 있다
        assertThat(login(email, AccessFixtures.PASSWORD).statusCode()).isEqualTo(401);
        HttpResponse<String> again = client().post("/api/auth/register", "{\"email\":\"" + email
                + "\",\"password\":\"" + NEW_PASSWORD + "\",\"name\":\"다시\",\"verified_token\":\""
                + fixtures().verifiedToken(email) + "\"}");
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(json.readTree(again.body()).get("id").asString()).isNotEqualTo(String.valueOf(me));
    }

    /**
     * 깨지면: 탈퇴한 사람의 이메일이 임시 비밀번호·가입 인증·초대 행에 남거나(-277 완료 조건), 탈퇴 전에 받아 둔 대기 초대로
     * 같은 이메일의 새 계정이 자동 참여하거나, 주소를 지운 대기 초대가 링크 초대처럼 열려 있거나, 다른 사람의 초대까지 지워지거나,
     * 탈퇴 뒤 팀장이 새로 초대해도 들어오지 못한다.
     */
    @Test
    void withdraw_erasesEmailFromRecoveryVerificationAndInvitations_andSameEmailCanBeInvitedAgain() {
        long me = fixtures().account();
        String email = fixtures().emailOf(me);
        var pendingTeam = fixtures().projectWithLeader();   // 아직 로그인하지 않아 대기 중인 초대
        var joinedTeam = fixtures().projectWithLeader();    // 초대를 받아 들어간 팀
        fixtures().member(joinedTeam.projectId(), me, ProjectRole.MEMBER);
        // 로그인하면 대기 초대가 자동 참여로 수락되므로, 초대는 로그인한 뒤에 보낸다
        ApiClient user = client().login(email, AccessFixtures.PASSWORD);
        ApiClient pendingLeader = client().login(pendingTeam.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(pendingLeader.post("/api/projects/" + pendingTeam.projectId() + "/invitations",
                "{\"emails\":[\"" + email + "\"]}").statusCode()).isEqualTo(201);
        long pending = db.queryForObject("SELECT id FROM invitation WHERE project_id = ? AND email = ?", Long.class,
                pendingTeam.projectId(), email);
        long accepted = db.queryForObject("""
                INSERT INTO invitation (project_id, email, token_hash, invited_by, accepted_at, accepted_account_id)
                VALUES (?, ?, ?, ?, now(), ?) RETURNING id
                """, Long.class, joinedTeam.projectId(), email, "h-acc-" + me, joinedTeam.leaderAccountId(), me);
        String otherEmail = AccessFixtures.uniqueEmail();
        long others = db.queryForObject("""
                INSERT INTO invitation (project_id, email, token_hash, invited_by) VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, joinedTeam.projectId(), otherEmail, "h-other-" + me, joinedTeam.leaderAccountId());
        // 임시 비밀번호: 이 계정에 발급된 열린 행과, 계정이 맞지 않아 한도만 센 행
        db.update("""
                INSERT INTO temporary_password (email, account_id, password_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, 'hash', now() + interval '30 minutes', now(), now())
                """, email, me);
        db.update("""
                INSERT INTO temporary_password (email, expires_at, closed_at, created_at, updated_at)
                VALUES (?, now(), now(), now(), now())
                """, email);
        fixtures().verifiedToken(email);   // 가입 인증 행
        // 다른 사람의 행: 지워지면 안 된다
        db.update("""
                INSERT INTO temporary_password (email, expires_at, closed_at, created_at, updated_at)
                VALUES (?, now(), now(), now(), now())
                """, otherEmail);
        fixtures().verifiedToken(otherEmail);

        assertThat(withdraw(user, AccessFixtures.PASSWORD).statusCode()).isEqualTo(204);

        assertThat(db.queryForObject("SELECT count(*) FROM temporary_password WHERE email = ? OR account_id = ?",
                Integer.class, email, me)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM email_verification WHERE email = ?", Integer.class, email))
                .isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM invitation WHERE email = ?", Integer.class, email)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM temporary_password WHERE email = ?", Integer.class,
                otherEmail)).as("다른 사람의 임시 비밀번호 요청은 그대로").isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM email_verification WHERE email = ?", Integer.class,
                otherEmail)).as("다른 사람의 가입 인증은 그대로").isEqualTo(1);
        assertThat(db.queryForObject("SELECT revoked_at IS NOT NULL FROM invitation WHERE id = ?", Boolean.class,
                pending)).as("대기 초대는 취소 — 주소 없는 링크 초대로 남지 않는다").isTrue();
        assertThat(db.queryForObject("SELECT accepted_account_id FROM invitation WHERE id = ?", Long.class, accepted))
                .as("팀의 초대 기록은 남는다").isEqualTo(me);
        assertThat(db.queryForMap("SELECT email, revoked_at FROM invitation WHERE id = ?", others))
                .as("다른 사람의 초대는 그대로").containsEntry("email", otherEmail).containsEntry("revoked_at", null);

        // 같은 이메일로 새로 가입해도 예전 초대로는 들어오지 않는다
        HttpResponse<String> again = client().post("/api/auth/register", "{\"email\":\"" + email
                + "\",\"password\":\"" + NEW_PASSWORD + "\",\"name\":\"다시\",\"verified_token\":\""
                + fixtures().verifiedToken(email) + "\"}");
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        long reborn = Long.parseLong(json.readTree(again.body()).get("id").asString());
        String memberCount = "SELECT count(*) FROM project_member WHERE project_id = ? AND account_id = ? AND active";
        assertThat(db.queryForObject(memberCount, Integer.class, pendingTeam.projectId(), reborn)).isZero();

        // 팀장이 새로 초대하면 다음 로그인에 들어온다 (Q05 자동 참여)
        HttpResponse<String> reinvite = pendingLeader.post("/api/projects/" + pendingTeam.projectId() + "/invitations",
                "{\"emails\":[\"" + email + "\"]}");
        assertThat(reinvite.statusCode()).as(reinvite.body()).isEqualTo(201);
        assertThat(login(email, NEW_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(db.queryForObject(memberCount, Integer.class, pendingTeam.projectId(), reborn)).isEqualTo(1);
    }
}
