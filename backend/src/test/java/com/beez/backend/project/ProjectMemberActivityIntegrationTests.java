package com.beez.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.support.RecordingMailGateway;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 프로젝트·멤버 활동 (S15P31A108-289, 계약 10-notification-activity-contract.md 5절·6.2). 생성·참여(링크 수락·로그인 자동 참여)·
 * 제외·나가기·위임·위임하고 나가기·탈퇴가 각각 활동을 한 건씩 남기는지, 같은 멤버에게 같은 동작이 되풀이돼도 키가 갈리는지,
 * 실패한 요청과 함께 활동도 되돌아가는지를 실제 DB·필터 체인 위에서 본다.
 */
class ProjectMemberActivityIntegrationTests extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate db;

    @Autowired
    ObjectMapper json;

    @Autowired
    RecordingMailGateway mail;

    /** 깨지면: 프로젝트를 만들어도 타임라인에 시작점이 없거나, 생성자의 참여가 따로 한 건 더 남는다. */
    @Test
    void create_recordsProjectCreatedOnly() {
        long me = fixtures().account();
        ApiClient user = client().login(fixtures().emailOf(me), AccessFixtures.PASSWORD);

        long projectId = Long.parseLong(read(user.post("/api/projects", "{\"name\":\"활동 시험\"}")).get("id").asString());

        assertThat(activities(projectId))
                .containsExactly("PROJECT_CREATED|project:" + projectId + ":created|PROJECT|" + projectId + "|HUMAN|" + me);
    }

    /**
     * 깨지면: 같은 멤버십 행이 되살아날 때마다 같은 키가 나와 두 번째 참여·제외가 조용히 건너뛰어지거나(-182 ON CONFLICT),
     * 자동 참여(REVIVE)에서 참여가 빠지거나, 이미 끝난 제외·나가기를 다시 보낼 때 한 건 더 남는다.
     */
    @Test
    void joinRemoveRejoinLeave_eachRecordsOnce_withDistinctKeys() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        long me = fixtures().account(email);
        ApiClient session = client().login(email, AccessFixtures.PASSWORD);

        // 1) 링크 수락(MEM-A05)
        long inv1 = linkInvitation(leader, p.projectId());
        long m = Long.parseLong(read(session.post("/api/invitations/accept", tokenBody(lastLinkToken)))
                .get("member").get("id").asString());
        // 2) 제외(MEM-A09). 다시 보내면 404 라 더 남지 않는다
        assertThat(leader.post(removePath(p.projectId(), m), "{}").statusCode()).isEqualTo(204);
        assertThat(leader.post(removePath(p.projectId(), m), "{}").statusCode()).isEqualTo(404);
        // 3) 이메일로 다시 초대 → 로그인 자동 참여(REVIVE)
        long inv2 = emailInvitation(leader, p.projectId(), email);
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(email) == 1);
        assertThat(client().login(email, AccessFixtures.PASSWORD).get("/api/projects/" + p.projectId()).statusCode())
                .isEqualTo(200);
        // 4) 두 번째 제외
        assertThat(leader.post(removePath(p.projectId(), m), "{}").statusCode()).isEqualTo(204);
        // 5) 새 링크로 돌아와 스스로 나가기(MEM-A10). 다시 보내면 404
        long inv3 = linkInvitation(leader, p.projectId());
        assertThat(session.post("/api/invitations/accept", tokenBody(lastLinkToken)).statusCode()).isEqualTo(200);
        assertThat(session.post(leavePath(p.projectId()), "{}").statusCode()).isEqualTo(204);
        assertThat(session.post(leavePath(p.projectId()), "{}").statusCode()).isEqualTo(404);

        String target = "|MEMBER|" + m + "|HUMAN|";
        assertThat(activities(p.projectId())).containsExactly(
                "MEMBER_JOINED|member:" + m + ":joined:" + inv1 + target + me,
                "MEMBER_REMOVED|member:" + m + ":removed:1" + target + p.leaderAccountId(),
                "MEMBER_JOINED|member:" + m + ":joined:" + inv2 + target + me,
                "MEMBER_REMOVED|member:" + m + ":removed:2" + target + p.leaderAccountId(),
                "MEMBER_JOINED|member:" + m + ":joined:" + inv3 + target + me,
                "MEMBER_LEFT|member:" + m + ":left:1" + target + me);
    }

    /**
     * 깨지면: 이미 팀원인 사람이 로그인할 때 대기 초대를 닫기만 하는 자동 참여(ALREADY_ACTIVE)가 참여를 한 번 더 남긴다
     * (-289 Jira 코멘트 — 들어온 게 아니라 초대만 닫는다).
     */
    @Test
    void autoJoin_alreadyActive_closesInvitationWithoutJoinActivity() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        ApiClient session = client().login(email, AccessFixtures.PASSWORD);

        long emailInv = emailInvitation(leader, p.projectId(), email);   // 대기로 남는 이메일 초대
        await().atMost(Duration.ofSeconds(10)).until(() -> mail.countTo(email) == 1);
        linkInvitation(leader, p.projectId());
        assertThat(session.post("/api/invitations/accept", tokenBody(lastLinkToken)).statusCode()).isEqualTo(200);
        client().login(email, AccessFixtures.PASSWORD);   // 자동 참여: 이미 팀원 → 이메일 초대만 닫음

        assertThat(db.queryForObject("SELECT accepted_at IS NOT NULL FROM invitation WHERE id = ?", Boolean.class,
                emailInv)).as("자동 참여가 실제로 돌았다").isTrue();
        assertThat(activities(p.projectId())).singleElement().asString().startsWith("MEMBER_JOINED|");
    }

    /**
     * 깨지면: 위임 대상(새 팀장)이 아닌 쪽에 남거나, 늦게 온 같은 위임(403)이 한 건 더 남기거나, 위임하고 나가기(-304)에서
     * 위임·나가기 중 하나가 빠지거나 순서가 뒤집히거나, 같은 사람이 두 번째로 팀장을 받을 때 키가 겹쳐 건너뛰어진다.
     */
    @Test
    void transfer_andLeaderLeave_recordTransferThenLeft() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        long bob = fixtures().account();
        long b = fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        long carol = fixtures().account();
        long c = fixtures().member(p.projectId(), carol, ProjectRole.MEMBER);
        long l = p.leaderMemberId();
        String transfer = "/api/projects/" + p.projectId() + "/leadership-transfer";

        assertThat(leader.post(transfer, "{\"target_member_id\":\"" + b + "\"}").statusCode()).isEqualTo(200);
        assertThat(leader.post(transfer, "{\"target_member_id\":\"" + b + "\"}").statusCode()).isEqualTo(403);
        // bob 이 leader 에게 넘기고 나간다 — 위임 다음에 나가기
        ApiClient bobSession = client().login(fixtures().emailOf(bob), AccessFixtures.PASSWORD);
        assertThat(bobSession.post(leavePath(p.projectId()), "{\"transfer_leader_to_member_id\":\"" + l + "\"}")
                .statusCode()).isEqualTo(204);
        // leader 가 두 번째로 팀장을 받은 뒤 carol 에게, carol 이 다시 leader 에게 — leader 의 세 번째가 아니라 두 번째 받음
        assertThat(leader.post(transfer, "{\"target_member_id\":\"" + c + "\"}").statusCode()).isEqualTo(200);
        ApiClient carolSession = client().login(fixtures().emailOf(carol), AccessFixtures.PASSWORD);
        assertThat(carolSession.post(transfer, "{\"target_member_id\":\"" + l + "\"}").statusCode()).isEqualTo(200);

        assertThat(activities(p.projectId())).containsExactly(
                "LEADER_TRANSFERRED|member:" + b + ":leader-transferred:1|MEMBER|" + b + "|HUMAN|" + p.leaderAccountId(),
                "LEADER_TRANSFERRED|member:" + l + ":leader-transferred:1|MEMBER|" + l + "|HUMAN|" + bob,
                "MEMBER_LEFT|member:" + b + ":left:1|MEMBER|" + b + "|HUMAN|" + bob,
                "LEADER_TRANSFERRED|member:" + c + ":leader-transferred:1|MEMBER|" + c + "|HUMAN|" + p.leaderAccountId(),
                "LEADER_TRANSFERRED|member:" + l + ":leader-transferred:2|MEMBER|" + l + "|HUMAN|" + carol);
    }

    /**
     * 깨지면: 위임하고 나가기가 위임 뒤 단계(인계 검사)에서 실패했는데 위임 활동만 커밋돼, 팀장이 그대로인데 타임라인에는 넘겼다고 보인다.
     */
    @Test
    void leaderLeave_failingAfterTransfer_rollsBackActivity() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        long c = fixtures().member(p.projectId(), fixtures().account(), ProjectRole.MEMBER);

        // 떠나는 본인에게 인계하라는 값은 위임을 마친 뒤 end() 에서 400
        HttpResponse<String> res = leader.post(leavePath(p.projectId()), "{\"transfer_leader_to_member_id\":\"" + c
                + "\",\"reassign_to_member_id\":\"" + p.leaderMemberId() + "\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(400);

        assertThat(db.queryForObject("SELECT role FROM project_member WHERE id = ?", String.class, p.leaderMemberId()))
                .isEqualTo("LEADER");
        assertThat(activities(p.projectId())).isEmpty();
    }

    /** 깨지면: 탈퇴로 팀 프로젝트에서 빠진 사람이 타임라인에 나간 기록 없이 사라지거나, 함께 삭제되는 혼자 쓰던 프로젝트에 남는다. */
    @Test
    void withdraw_recordsLeftInTeamProjectsOnly() {
        var solo = fixtures().projectWithLeader();
        long me = solo.leaderAccountId();
        var team = fixtures().projectWithLeader();
        long myMemberId = fixtures().member(team.projectId(), me, ProjectRole.MEMBER);
        ApiClient user = client().login(solo.leaderEmail(), AccessFixtures.PASSWORD);

        assertThat(user.post("/api/me/withdraw", "{\"password\":\"" + AccessFixtures.PASSWORD + "\"}").statusCode())
                .isEqualTo(204);

        assertThat(activities(team.projectId())).containsExactly(
                "MEMBER_LEFT|member:" + myMemberId + ":left:1|MEMBER|" + myMemberId + "|HUMAN|" + me);
        assertThat(activities(solo.projectId())).isEmpty();
    }

    // ---- helpers ----

    private String lastLinkToken;

    /** 링크 초대를 만들고 id 를 돌려준다. 토큰은 {@link #lastLinkToken} 에 둔다 */
    private long linkInvitation(ApiClient leader, long projectId) {
        HttpResponse<String> res = leader.post("/api/projects/" + projectId + "/invitations", "{}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        JsonNode body = read(res);
        lastLinkToken = body.get("invite_url").asString().replaceAll(".*token=", "");
        return Long.parseLong(body.get("invitation").get("id").asString());
    }

    private long emailInvitation(ApiClient leader, long projectId, String email) {
        HttpResponse<String> res = leader.post("/api/projects/" + projectId + "/invitations",
                "{\"emails\":[\"" + email + "\"]}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return Long.parseLong(read(res).get("results").get(0).get("invitation_id").asString());
    }

    /** 이 프로젝트의 활동, 저장 순: 유형|키|대상 종류|대상 id|행위자 종류|행위 계정 */
    private List<String> activities(long projectId) {
        return db.queryForList("""
                SELECT event_type || '|' || dedupe_key || '|' || ref_kind || '|' || ref_id || '|' || actor_kind || '|' || actor_id
                  FROM activity_event WHERE project_id = ? ORDER BY id
                """, String.class, projectId);
    }

    private static String removePath(long projectId, long memberId) {
        return "/api/projects/" + projectId + "/members/" + memberId + "/remove";
    }

    private static String leavePath(long projectId) {
        return "/api/projects/" + projectId + "/leave";
    }

    private static String tokenBody(String token) {
        return "{\"token\":\"" + token + "\"}";
    }

    private JsonNode read(HttpResponse<String> res) {
        assertThat(res.statusCode()).as(res.body()).isBetween(200, 299);
        return json.readTree(res.body());
    }
}
