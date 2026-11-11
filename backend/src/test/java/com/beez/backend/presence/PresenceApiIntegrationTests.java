package com.beez.backend.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.AccessFixtures.ProjectWithLeader;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** MON-A06·A07 팀원 접속 상태·위치 (S15P31A108-386). 한도·TTL 은 운영과 같은 설정을 읽는다. */
class PresenceApiIntegrationTests extends IntegrationTestSupport {

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    JdbcTemplate db;

    @Autowired
    PresenceStore store;

    @Autowired
    PresenceService service;

    @Autowired
    PresenceProperties properties;

    private static String path(long projectId) {
        return "/api/projects/" + projectId + "/presence";
    }

    private record Teammate(long accountId, long memberId, ApiClient api) {
    }

    private Teammate join(long projectId) {
        String email = AccessFixtures.uniqueEmail();
        long account = fixtures().account(email);
        long member = fixtures().member(projectId, account, ProjectRole.MEMBER);
        return new Teammate(account, member, client().login(email, AccessFixtures.PASSWORD));
    }

    private JsonNode items(ApiClient api, long projectId) throws Exception {
        HttpResponse<String> res = api.get(path(projectId));
        assertThat(res.statusCode()).isEqualTo(200);
        return json.readTree(res.body()).get("items");
    }

    private static JsonNode itemOf(JsonNode items, long memberId) {
        for (JsonNode item : items) {
            if (item.get("member_id").asText().equals(Long.toString(memberId))) {
                return item;
            }
        }
        return null;
    }

    /**
     * 깨지면: 보고한 위치가 GET 에 안 보이거나, 접속한 적 없는 팀원이 목록에서 빠지거나(HUD 팀 목록에서 사라진다),
     * 접속한 적 없는 사람에게 가짜 last_seen_at 이 붙는다.
     */
    @Test
    void 보고하면_online과_위치가_보이고_접속한_적_없는_팀원도_목록에_있다() throws Exception {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        Teammate silent = join(p.projectId());

        HttpResponse<String> put = leader.put(path(p.projectId()), "{\"zone\":\"dev\",\"seat\":\"A-3\",\"panel\":\"tasks\"}");
        assertThat(put.statusCode()).isEqualTo(204);
        assertThat(put.body()).isEmpty();

        JsonNode items = items(leader, p.projectId());
        assertThat(items).hasSize(2);
        JsonNode me = itemOf(items, p.leaderMemberId());
        assertThat(me.get("online").asBoolean()).isTrue();
        assertThat(me.get("zone").asText()).isEqualTo("dev");
        assertThat(me.get("seat").asText()).isEqualTo("A-3");
        assertThat(me.get("panel").asText()).isEqualTo("tasks");
        assertThat(me.get("last_seen_at").asText()).endsWith("Z");

        JsonNode other = itemOf(items, silent.memberId());
        assertThat(other.get("online").asBoolean()).isFalse();
        assertThat(other.has("last_seen_at")).isFalse();
        assertThat(other.has("zone")).isFalse();

        // 팀원도 같은 목록을 본다
        assertThat(items(silent.api(), p.projectId())).hasSize(2);
    }

    /** 깨지면: 같은 값을 다시 보내면 오류가 나거나, 본문 없는 신호가 거절되거나, 이전 위치가 남는다(PUT 은 마지막 값 덮어쓰기). */
    @Test
    void 같은_보고를_되풀이해도_204이고_본문이_없으면_위치를_비운_신호로_덮어쓴다() throws Exception {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        assertThat(leader.put(path(p.projectId()), "{\"zone\":\"lobby\"}").statusCode()).isEqualTo(204);
        assertThat(leader.put(path(p.projectId()), "{\"zone\":\"lobby\"}").statusCode()).isEqualTo(204);
        assertThat(leader.put(path(p.projectId()), "{}").statusCode()).isEqualTo(204);

        JsonNode me = itemOf(items(leader, p.projectId()), p.leaderMemberId());
        assertThat(me.get("online").asBoolean()).isTrue();
        assertThat(me.has("zone")).isFalse();
    }

    /** 깨지면: 허용 값 밖의 문자열이 저장돼 FE 가 모르는 zone 을 받는다. 거절된 요청이 값을 바꾼다. */
    @Test
    void 허용_값_밖이나_너무_긴_값은_400이고_저장하지_않는다() throws Exception {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(leader.put(path(p.projectId()), "{\"zone\":\"dev\"}").statusCode()).isEqualTo(204);

        for (String bad : new String[] {
                "{\"zone\":\"desk\"}",
                "{\"zone\":\"dev\",\"seat\":\"" + "A".repeat(17) + "\"}",
                "{\"seat\":\"a b\"}",
                "{\"zone\":\"hr\",\"panel\":\"nope\"}"}) {
            HttpResponse<String> res = leader.put(path(p.projectId()), bad);
            assertThat(res.statusCode()).as(bad).isEqualTo(400);
            assertThat(res.body()).contains("validation_failed");
        }
        assertThat(itemOf(items(leader, p.projectId()), p.leaderMemberId()).get("zone").asText()).isEqualTo("dev");
    }

    /** 깨지면: 명세에 있는 activity 필드를 보낸 FE 요청이 400 으로 깨진다 (MVP 에서는 무시한다). */
    @Test
    void activity를_보내도_무시하고_204다() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        HttpResponse<String> res = leader.put(path(p.projectId()),
                "{\"zone\":\"dev\",\"activity\":{\"action\":\"EDITING\",\"target_type\":\"TASK\",\"target_id\":\"1\"}}");

        assertThat(res.statusCode()).isEqualTo(204);
    }

    /** 깨지면: 비멤버가 팀 위치를 엿보거나 남의 프로젝트에 위치를 쓴다. 로그인 없이 호출된다. */
    @Test
    void 비멤버와_다른_프로젝트와_비로그인은_거절된다() throws Exception {
        ProjectWithLeader a = fixtures().projectWithLeader();
        ProjectWithLeader b = fixtures().projectWithLeader();
        ApiClient leaderA = client().login(a.leaderEmail(), AccessFixtures.PASSWORD);
        ApiClient leaderB = client().login(b.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(leaderA.put(path(a.projectId()), "{\"zone\":\"dev\"}").statusCode()).isEqualTo(204);

        // B 의 팀장은 A 를 읽지도 쓰지도 못한다 (존재를 숨기는 404)
        assertThat(leaderB.get(path(a.projectId())).statusCode()).isEqualTo(404);
        assertThat(leaderB.put(path(a.projectId()), "{\"zone\":\"hr\"}").statusCode()).isEqualTo(404);
        // 격리: A 의 보고는 B 의 목록에 없다
        JsonNode itemsB = items(leaderB, b.projectId());
        assertThat(itemsB).hasSize(1);
        assertThat(itemOf(itemsB, b.leaderMemberId()).get("online").asBoolean()).isFalse();
        // 거절된 쓰기는 A 의 값을 바꾸지 않는다
        assertThat(itemOf(items(leaderA, a.projectId()), a.leaderMemberId()).get("zone").asText()).isEqualTo("dev");

        ApiClient anonymous = client();
        assertThat(anonymous.get(path(a.projectId())).statusCode()).isEqualTo(401);
        assertThat(anonymous.put(path(a.projectId()), "{}").statusCode()).isEqualTo(401);
    }

    /** 깨지면: 에이전트 토큰이 사람의 위치를 쓰거나 읽어 에이전트 세션과 사람 접속이 섞인다. */
    @Test
    void 에이전트_호출자는_보고도_조회도_거절된다() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        long token = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        CallerContext agent = CallerContext.agent(p.leaderAccountId(), token);

        assertThatThrownBy(() -> service.report(agent, p.projectId(), new PresenceReportRequest("dev", null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.list(agent, p.projectId()))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN));
        assertThat(store.find(p.projectId(), p.leaderMemberId())).isEmpty();
    }

    /** 깨지면: 보고 한도가 없어 한 클라이언트가 서버를 두드릴 수 있다. 한도가 다른 사람·다른 프로젝트까지 막는다. */
    @Test
    void 한도를_넘으면_429이고_다른_사람과_다른_프로젝트는_막지_않는다() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        Teammate other = join(p.projectId());
        long second = fixtures().project(p.leaderAccountId());
        fixtures().member(second, p.leaderAccountId(), ProjectRole.LEADER);

        for (int i = 0; i < properties.rateLimit(); i++) {
            assertThat(leader.put(path(p.projectId()), "{\"zone\":\"dev\"}").statusCode()).isEqualTo(204);
        }
        HttpResponse<String> limited = leader.put(path(p.projectId()), "{\"zone\":\"dev\"}");
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.body()).contains("rate_limited");

        assertThat(other.api().put(path(p.projectId()), "{\"zone\":\"hr\"}").statusCode()).isEqualTo(204);
        assertThat(leader.put(path(second), "{\"zone\":\"dev\"}").statusCode()).isEqualTo(204);
    }

    /** 깨지면: 위치 보고가 활동 기록·알림을 만들어 15초마다 타임라인과 알림함이 채워진다. */
    @Test
    void 보고는_활동과_알림을_만들지_않는다() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        long activityBefore = count("SELECT count(*) FROM activity_event WHERE project_id=?", p.projectId());
        long notificationBefore = count("SELECT count(*) FROM notification WHERE project_id=?", p.projectId());

        assertThat(leader.put(path(p.projectId()), "{\"zone\":\"dev\",\"seat\":\"A-3\"}").statusCode()).isEqualTo(204);
        assertThat(leader.get(path(p.projectId())).statusCode()).isEqualTo(200);

        assertThat(count("SELECT count(*) FROM activity_event WHERE project_id=?", p.projectId())).isEqualTo(activityBefore);
        assertThat(count("SELECT count(*) FROM notification WHERE project_id=?", p.projectId())).isEqualTo(notificationBefore);
    }

    /** 깨지면: 나간 사람의 옛 위치가 남아, 목록에서 빠지더라도 메모리에 남거나 다시 들어오면 되살아난다. */
    @Test
    void 프로젝트를_나가면_위치가_지워지고_목록에서_빠진다() throws Exception {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        Teammate mate = join(p.projectId());
        assertThat(mate.api().put(path(p.projectId()), "{\"zone\":\"dev\",\"seat\":\"B-1\"}").statusCode()).isEqualTo(204);
        assertThat(store.find(p.projectId(), mate.memberId())).isPresent();

        assertThat(mate.api().post("/api/projects/" + p.projectId() + "/leave", "{}").statusCode()).isEqualTo(204);

        assertThat(store.find(p.projectId(), mate.memberId())).isEmpty();
        assertThat(items(leader, p.projectId())).hasSize(1);
        assertThat(mate.api().get(path(p.projectId())).statusCode()).isEqualTo(404);
    }

    /** 깨지면: 삭제한 프로젝트의 팀 위치가 메모리에 남는다. */
    @Test
    void 프로젝트를_삭제하면_모든_위치가_지워진다() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        assertThat(leader.put(path(p.projectId()), "{\"zone\":\"dev\"}").statusCode()).isEqualTo(204);
        assertThat(store.find(p.projectId(), p.leaderMemberId())).isPresent();

        assertThat(leader.delete("/api/projects/" + p.projectId(), "{\"confirm_name\":\"fixture-project\"}").statusCode())
                .isEqualTo(204);

        assertThat(store.find(p.projectId(), p.leaderMemberId())).isEmpty();
        assertThat(leader.get(path(p.projectId())).statusCode()).isEqualTo(404);
    }

    /** 깨지면: 계정을 탈퇴해도 그 사람의 위치가 팀 프로젝트에 남는다. */
    @Test
    void 계정을_탈퇴하면_위치가_지워진다() {
        ProjectWithLeader p = fixtures().projectWithLeader();
        Teammate mate = join(p.projectId());
        assertThat(mate.api().put(path(p.projectId()), "{\"zone\":\"dev\"}").statusCode()).isEqualTo(204);
        assertThat(store.find(p.projectId(), mate.memberId())).isPresent();

        HttpResponse<String> res = mate.api().post("/api/me/withdraw", "{\"password\":\"" + AccessFixtures.PASSWORD + "\"}");
        assertThat(res.statusCode()).isIn(200, 204);

        assertThat(store.find(p.projectId(), mate.memberId())).isEmpty();
    }

    private long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }
}
