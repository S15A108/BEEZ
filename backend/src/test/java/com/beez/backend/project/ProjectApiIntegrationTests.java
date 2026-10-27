package com.beez.backend.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * PRJ-A01 ~ A05 (S15P31A108-173). Jira 완료 조건 "무권한 수정·삭제 거부", "삭제 프로젝트 비노출"과
 * -172 합의 4(삭제 시 토큰 같은 트랜잭션 폐기), 00-common 3절(권한 필터는 페이지 전)을 실제 DB·필터 체인 위에서 확인한다.
 */
class ProjectApiIntegrationTests extends IntegrationTestSupport {

    @Autowired
    ObjectMapper json;

    @Autowired
    ProjectAccessChecker access;

    @Autowired
    JdbcTemplate jdbc;

    /** 깨지면: 생성자가 팀장이 아니거나 멤버십이 안 생겨 자기 프로젝트를 못 본다 (PRJ-A01 처리 규칙). */
    @Test
    void create_makesCreatorLeader_visibleInListAndDetail() {
        ApiClient me = loginAsNewAccount();

        HttpResponse<String> created = me.post("/api/projects",
                "{\"name\":\"  BEEZ  \",\"description\":\"승인 설계 기반 협업\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = read(created);
        String id = body.get("id").asString();
        assertThat(body.get("name").asString()).isEqualTo("BEEZ");
        assertThat(body.get("my_role").asString()).isEqualTo("LEADER");
        assertThat(body.has("current_baseline")).isFalse();

        JsonNode detail = read(me.get("/api/projects/" + id));
        assertThat(detail.get("my_role").asString()).isEqualTo("LEADER");
        assertThat(ids(read(me.get("/api/projects")))).containsExactly(id);
    }

    /** 깨지면: 팀원이 프로젝트를 바꾸거나 지울 수 있다, 또는 남의 프로젝트 존재가 드러난다 (Jira 완료 조건 1). */
    @Test
    void updateAndDelete_leaderOnly_outsiderSeesNothing() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        ApiClient member = client().login(fixtures().emailOf(bob), AccessFixtures.PASSWORD);
        ApiClient outsider = loginAsNewAccount();
        String path = "/api/projects/" + p.projectId();

        assertThat(member.get(path).statusCode()).isEqualTo(200);
        assertThat(member.patch(path, "{\"name\":\"바꿈\"}").statusCode()).isEqualTo(403);
        assertThat(member.delete(path, "{\"confirm_name\":\"fixture-project\"}").statusCode()).isEqualTo(403);
        assertThat(outsider.get(path).statusCode()).isEqualTo(404);
        assertThat(outsider.patch(path, "{\"name\":\"바꿈\"}").statusCode()).isEqualTo(404);

        assertThat(leader.patch(path, "{}").statusCode()).isEqualTo(400);
        leader.patch(path, "{\"description\":\"설명\"}");
        JsonNode updated = read(leader.patch(path, "{\"name\":\"새 이름\",\"description\":\"\"}"));
        assertThat(updated.get("name").asString()).isEqualTo("새 이름");
        assertThat(updated.has("description")).isFalse();
    }

    /** 깨지면: 삭제한 프로젝트가 다시 보이거나, 그 프로젝트 에이전트가 계속 MCP 를 쓸 수 있다 (완료 조건 2, -172 합의 4). */
    @Test
    void delete_hidesProject_andRevokesItsTokens() {
        var p = fixtures().projectWithLeader();
        long token = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + p.projectId();

        HttpResponse<String> mismatch = leader.delete(path, "{\"confirm_name\":\"다른 이름\"}");
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(mismatch.body()).contains("confirmation_mismatch");

        assertThat(leader.delete(path, "{\"confirm_name\":\"fixture-project\"}").statusCode()).isEqualTo(204);
        assertThat(leader.get(path).statusCode()).isEqualTo(404);
        assertThat(ids(read(leader.get("/api/projects")))).doesNotContain(String.valueOf(p.projectId()));
        assertThatThrownBy(() -> access.requireMember(CallerContext.agent(p.leaderAccountId(), token), p.projectId()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("token_revoked"));
    }

    /** 깨지면: 권한 필터를 페이지를 자른 뒤에 걸어 페이지가 비거나 남의 프로젝트가 섞인다 (00-common 3절). */
    @Test
    void list_pagesThroughOnlyMyProjects() {
        ApiClient me = loginAsNewAccount();
        ApiClient other = loginAsNewAccount();
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mine.add(read(me.post("/api/projects", "{\"name\":\"mine-" + i + "\"}")).get("id").asString());
            other.post("/api/projects", "{\"name\":\"other-" + i + "\"}");
        }

        JsonNode first = read(me.get("/api/projects?limit=2"));
        assertThat(first.get("has_more").asBoolean()).isTrue();
        JsonNode second = read(me.get("/api/projects?limit=2&cursor=" + first.get("next_cursor").asString()));
        assertThat(second.get("has_more").asBoolean()).isFalse();

        List<String> seen = new ArrayList<>(ids(first));
        seen.addAll(ids(second));
        assertThat(seen).containsExactly(mine.get(2), mine.get(1), mine.get(0));
        // 빈 cursor 는 첫 페이지 (작업 목록과 같은 동작)
        assertThat(ids(read(me.get("/api/projects?limit=2&cursor=")))).isEqualTo(ids(first));
    }

    /** 깨지면: 기준 설계가 발행된 프로젝트의 목록·상세가 500 이 나거나 current_baseline 이 빠진다 (00-common Project). */
    @Test
    void publishedProject_showsCurrentBaseline_inListAndDetail() {
        var p = fixtures().projectWithLeader();
        long baselineId = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, p.projectId(), p.leaderAccountId());
        jdbc.update("UPDATE project SET current_baseline_id = ? WHERE id = ?", baselineId, p.projectId());
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        JsonNode detail = read(leader.get("/api/projects/" + p.projectId())).get("current_baseline");
        assertThat(detail.get("id").asString()).isEqualTo(String.valueOf(baselineId));
        assertThat(detail.get("version_no").asInt()).isEqualTo(1);
        assertThat(detail.has("created_at")).isTrue();
        JsonNode listed = read(leader.get("/api/projects")).get("items").get(0).get("current_baseline");
        assertThat(listed.get("id").asString()).isEqualTo(String.valueOf(baselineId));
    }

    private ApiClient loginAsNewAccount() {
        long accountId = fixtures().account();
        return client().login(fixtures().emailOf(accountId), AccessFixtures.PASSWORD);
    }

    private JsonNode read(HttpResponse<String> res) {
        return json.readTree(res.body());
    }

    private static List<String> ids(JsonNode page) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            out.add(item.get("id").asString());
        }
        return out;
    }
}
