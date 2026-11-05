package com.beez.backend.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.AccessFixtures.ProjectWithLeader;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** MON-A04 프로젝트 활동 타임라인 (S15P31A108-183). 실제 HTTP·세션·SQL 로 본다. */
class ActivityTimelineApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    JdbcTemplate sql;

    @Autowired
    ObjectMapper json;

    /** 입구(-182)를 거치지 않고 바로 넣는다 — 조회만 본다. 비비·시스템이면 actorAccountId 는 null. */
    private long activity(long projectId, String actorKind, Long actorAccountId, String type) {
        return activity(projectId, actorKind, actorAccountId, null, type, null);
    }

    private long activity(long projectId, String actorKind, Long actorAccountId, Long agentSessionId, String type,
                          Long changeProposalId) {
        return sql.queryForObject("""
                insert into activity_event (project_id, actor_kind, actor_id, agent_session_id, event_type, ref_kind, ref_id,
                                            change_proposal_id, dedupe_key)
                values (?, ?, ?, ?, ?, 'PROJECT', ?, ?, ?) returning id
                """, Long.class, projectId, actorKind, actorAccountId, agentSessionId, type, projectId, changeProposalId,
                "project:" + projectId + ":fixture:" + UUID.randomUUID());
    }

    private JsonNode body(HttpResponse<String> res) {
        return json.readTree(res.body());
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(item -> out.add(item.get("id").asString()));
        return out;
    }

    private static String s(long id) {
        return String.valueOf(id);
    }

    /** 깨지면 커서 경계에서 활동이 빠지거나 겹치고, 다른 프로젝트의 활동이 섞인다. */
    @Test
    void pagesByCursor_newestFirst_withoutGapOrOverlap_andOnlyThisProject() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long otherProject = fixtures().project(p.leaderAccountId());
        long first = activity(p.projectId(), "HUMAN", p.leaderAccountId(), "TASK_CREATED");
        activity(otherProject, "SYSTEM", null, "TASK_CREATED");
        long second = activity(p.projectId(), "SYSTEM", null, "TASK_STARTED");
        long third = activity(p.projectId(), "HUMAN", p.leaderAccountId(), "TASK_COMPLETED");
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String base = "/api/projects/" + p.projectId() + "/activities";

        JsonNode page1 = body(me.get(base + "?limit=2"));
        assertThat(ids(page1)).containsExactly(s(third), s(second));
        assertThat(page1.get("has_more").asBoolean()).isTrue();

        JsonNode page2 = body(me.get(base + "?limit=2&cursor=" + page1.get("next_cursor").asString()));
        assertThat(ids(page2)).containsExactly(s(first));
        assertThat(page2.get("has_more").asBoolean()).isFalse();
        assertThat(page2.has("next_cursor")).isFalse();

        JsonNode human = page2.get("items").get(0);
        assertThat(human.get("actor_type").asString()).isEqualTo("HUMAN");
        assertThat(human.get("actor_account_id").asString()).isEqualTo(s(p.leaderAccountId()));
        assertThat(human.has("actor_name")).isTrue();
        assertThat(human.get("target_type").asString()).isEqualTo("PROJECT");
        assertThat(human.get("target_id").asString()).isEqualTo(s(p.projectId()));
        assertThat(human.get("result").asString()).isEqualTo("SUCCESS");
        JsonNode system = page1.get("items").get(1);
        assertThat(system.has("actor_account_id")).isFalse();
        assertThat(system.has("actor_name")).isFalse();
    }

    /** 깨지면 프로젝트 밖 사람이 타임라인을 보거나, 나간 멤버가 계속 본다(명세 MEMBER 권한, 비멤버 404). */
    @Test
    void nonMember_and_leftMember_are404_anonymous401() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        activity(p.projectId(), "SYSTEM", null, "TASK_STARTED");
        long stranger = fixtures().account();
        long leaver = fixtures().account();
        long leaverMembership = fixtures().member(p.projectId(), leaver, ProjectRole.MEMBER);
        String path = "/api/projects/" + p.projectId() + "/activities";

        ApiClient strangerClient = client().login(fixtures().emailOf(stranger), AccessFixtures.PASSWORD);
        HttpResponse<String> res = strangerClient.get(path);
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(body(res).get("error").get("code").asString()).isEqualTo("not_found");

        ApiClient leaverClient = client().login(fixtures().emailOf(leaver), AccessFixtures.PASSWORD);
        assertThat(leaverClient.get(path).statusCode()).isEqualTo(200);
        fixtures().removeMember(leaverMembership);
        assertThat(leaverClient.get(path).statusCode()).isEqualTo(404);

        assertThat(client().get(path).statusCode()).isEqualTo(401);
        // 권한 확인이 필터 해석보다 먼저다 — 잘못된 필터여도 비로그인 401·비멤버 404
        assertThat(client().get(path + "?actor_type=ROBOT").statusCode()).isEqualTo(401);
        assertThat(strangerClient.get(path + "?actor_type=ROBOT").statusCode()).isEqualTo(404);
        assertThat(strangerClient.get(path + "?cursor=abc").statusCode()).isEqualTo(404);
    }

    /**
     * 깨지면 '사람/에이전트별' 화면에서 그 멤버의 에이전트 활동이 빠지거나 남의 활동이 섞이고, 한 제안의 지나온 길에 다른 제안이 끼며,
     * 필터가 페이지를 자른 뒤에 걸려 빈 페이지가 생긴다.
     */
    @Test
    void filters_byMember_actorType_type_changeProposal_beforePaging() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long teammate = fixtures().account();
        long teammateMember = fixtures().member(p.projectId(), teammate, ProjectRole.MEMBER);
        long token = fixtures().agentToken(p.projectId(), teammate);
        long session = sql.queryForObject(
                "insert into agent_session (agent_token_id, session_key) values (?, 'fixture') returning id",
                Long.class, token);
        long baseline = sql.queryForObject(
                "insert into baseline (project_id, version_no, published_by) values (?, 1, ?) returning id",
                Long.class, p.projectId(), p.leaderAccountId());
        long proposal = sql.queryForObject("""
                insert into change_proposal (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                values (?, 'CP-1', 'PROPOSED', ?, 'HUMAN', ?, '시험') returning id
                """, Long.class, p.projectId(), baseline, teammate);

        long byTeammate = activity(p.projectId(), "HUMAN", teammate, null, "CHANGE_PROPOSAL_SUBMITTED", proposal);
        long byTeammateAgent = activity(p.projectId(), "AGENT", teammate, session, "TASK_STARTED", null);
        long byLeader = activity(p.projectId(), "HUMAN", p.leaderAccountId(), null, "CHANGE_PROPOSAL_APPROVED", proposal);
        long byBee = activity(p.projectId(), "BEE", null, "MEETING_OPENED");
        for (int i = 0; i < 3; i++) {
            activity(p.projectId(), "SYSTEM", null, "TASK_STARTED");   // 필터 뒤에 잘려야 할 최신 행들
        }
        ApiClient me = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String base = "/api/projects/" + p.projectId() + "/activities?limit=2";

        assertThat(ids(body(me.get(base + "&member_id=" + teammateMember))))
                .containsExactly(s(byTeammateAgent), s(byTeammate));
        JsonNode agent = body(me.get(base + "&actor_type=agent")).get("items").get(0);
        assertThat(agent.get("id").asString()).isEqualTo(s(byTeammateAgent));
        assertThat(agent.get("agent_session_id").asString()).isEqualTo(s(session));
        assertThat(agent.get("actor_name").asString()).isEqualTo("fixture-token");
        assertThat(ids(body(me.get(base + "&agent_session_id=" + session)))).containsExactly(s(byTeammateAgent));
        assertThat(ids(body(me.get(base + "&actor_type=BEE")))).containsExactly(s(byBee));
        assertThat(ids(body(me.get(base + "&change_proposal_id=" + proposal)))).containsExactly(s(byLeader), s(byTeammate));
        assertThat(ids(body(me.get(base + "&type=CHANGE_PROPOSAL_APPROVED")))).containsExactly(s(byLeader));
        // 빈 값은 필터 없음('전체' 화면)
        assertThat(body(me.get(base + "&actor_type=&member_id=")).get("has_more").asBoolean()).isTrue();

        HttpResponse<String> bad = me.get(base + "&actor_type=ROBOT");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(body(bad).get("error").get("code").asString()).isEqualTo("validation_failed");
    }
}
