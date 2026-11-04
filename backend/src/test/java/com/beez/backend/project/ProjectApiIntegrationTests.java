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
        // 설명 길이는 앞뒤 공백을 뺀 뒤 2000자까지
        String full = "가".repeat(2000);
        assertThat(leader.patch(path, "{\"description\":\"  " + full + "  \"}").statusCode()).isEqualTo(200);
        assertThat(leader.patch(path, "{\"description\":\"" + full + "가\"}").statusCode()).isEqualTo(400);
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

        // 확인 입력도 이름처럼 앞뒤 공백을 빼고 비교한다
        assertThat(leader.delete(path, "{\"confirm_name\":\"  fixture-project \"}").statusCode()).isEqualTo(204);
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

    /** 깨지면: 남의 프로젝트나 삭제된 프로젝트의 초기 설정 현황이 드러난다 (S15P31A108-239 완료 조건 2). */
    @Test
    void onboarding_outsiderAndArchivedProject_notFound() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + p.projectId() + "/onboarding";

        assertThat(leader.get(path).statusCode()).isEqualTo(200);
        assertThat(loginAsNewAccount().get(path).statusCode()).isEqualTo(404);
        fixtures().archiveProject(p.projectId());
        assertThat(leader.get(path).statusCode()).isEqualTo(404);
    }

    /**
     * 깨지면: 문서 업로드·승인, 기준 발행, 팀원 변동, 내 에이전트 연결이 현황에 반영되지 않는다 (S15P31A108-239 완료 조건 3).
     * REQUIREMENTS 는 DB 에 REQ_SPEC 으로 저장되므로 이름 변환이 빠지면 여기서 깨진다.
     */
    @Test
    void onboarding_reflectsDocumentsBaselineMembersAndMyAgent() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + p.projectId() + "/onboarding";

        JsonNode empty = read(leader.get(path));
        assertThat(empty.get("required_documents")).hasSize(4);
        assertThat(requiredDocument(empty, "PLAN").get("uploaded").asBoolean()).isFalse();
        assertThat(empty.get("baseline_published").asBoolean()).isFalse();
        assertThat(empty.get("member_count").asInt()).isEqualTo(1);
        assertThat(empty.get("my_agent_connected").asBoolean()).isFalse();

        documentWithVersion(p, "PLAN", "DRAFT");
        documentWithVersion(p, "REQ_SPEC", "APPROVED");
        long baselineId = jdbc.queryForObject(
                "INSERT INTO baseline (project_id, version_no, published_by) VALUES (?, 1, ?) RETURNING id",
                Long.class, p.projectId(), p.leaderAccountId());
        jdbc.update("UPDATE project SET current_baseline_id = ? WHERE id = ?", baselineId, p.projectId());
        long bob = fixtures().account();
        long bobMember = fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        // 다른 팀원의 연결은 내 연결이 아니다
        connectAgent(fixtures().agentToken(p.projectId(), bob));

        JsonNode filled = read(leader.get(path));
        assertThat(requiredDocument(filled, "PLAN").get("uploaded").asBoolean()).isTrue();
        assertThat(requiredDocument(filled, "PLAN").get("approved").asBoolean()).isFalse();
        assertThat(requiredDocument(filled, "REQUIREMENTS").get("uploaded").asBoolean()).isTrue();
        assertThat(requiredDocument(filled, "REQUIREMENTS").get("approved").asBoolean()).isTrue();
        assertThat(requiredDocument(filled, "ERD").get("uploaded").asBoolean()).isFalse();
        assertThat(filled.get("baseline_published").asBoolean()).isTrue();
        assertThat(filled.get("member_count").asInt()).isEqualTo(2);
        assertThat(filled.get("my_agent_connected").asBoolean()).isFalse();

        long myToken = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        connectAgent(myToken);
        fixtures().removeMember(bobMember);
        JsonNode connected = read(leader.get(path));
        assertThat(connected.get("my_agent_connected").asBoolean()).isTrue();
        assertThat(connected.get("member_count").asInt()).isEqualTo(1);

        // 폐기된 토큰의 세션은 연결로 보지 않는다
        fixtures().revokeToken(myToken);
        assertThat(read(leader.get(path)).get("my_agent_connected").asBoolean()).isFalse();
    }

    private void documentWithVersion(AccessFixtures.ProjectWithLeader p, String dbType, String status) {
        long document = jdbc.queryForObject(
                "INSERT INTO document (project_id, type, title, created_by) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, p.projectId(), dbType, dbType, p.leaderAccountId());
        jdbc.update("""
                INSERT INTO document_version (document_id, version_no, status, storage_key, content_hash, content_text,
                                              created_by, approved_by, approved_at)
                VALUES (?, 1, ?, 'k', ?, 'text', ?, CASE WHEN ? = 'APPROVED' THEN ? END,
                        CASE WHEN ? = 'APPROVED' THEN now() END)
                """, document, status, "h-" + document, p.leaderAccountId(), status, p.leaderAccountId(), status);
    }

    private void connectAgent(long tokenId) {
        jdbc.update("INSERT INTO agent_session (agent_token_id, session_key) VALUES (?, ?)", tokenId, "s-" + tokenId);
    }

    private static JsonNode requiredDocument(JsonNode body, String kind) {
        for (JsonNode n : body.get("required_documents")) {
            if (kind.equals(n.get("kind").asString())) {
                return n;
            }
        }
        throw new AssertionError("종류 없음: " + kind);
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
