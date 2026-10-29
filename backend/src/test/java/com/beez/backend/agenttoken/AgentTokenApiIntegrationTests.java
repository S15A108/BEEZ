package com.beez.backend.agenttoken;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AGT-A01 ~ A03 + /mcp 토큰 검증 (S15P31A108-175). Jira 완료 조건 "폐기 토큰 즉시 거부", "토큰 원문 미노출"을
 * 실제 DB·필터 체인 위에서 확인한다.
 * <p>
 * /mcp 도구(-176~)가 아직 없어 없는 경로 {@link #MCP_PROBE} 를 부른다. 검증을 통과하면 핸들러가 없어 404, 막히면 401 이다.
 */
class AgentTokenApiIntegrationTests extends IntegrationTestSupport {

    static final String MCP_PROBE = "/mcp/__probe";

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcTemplate jdbc;

    /** 깨지면: 발급한 토큰으로 MCP 에 붙을 수 없거나, 평문이 발급 응답 밖(목록·캐시)으로 샌다 (AGT-A01·A02, NFR-03). */
    @Test
    void issue_returnsPlainOnce_andTokenWorksOnMcp() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);

        // label 은 선택이라 본문 없이도 발급된다
        HttpResponse<String> issued = leader.post(agentTokens(p.projectId()), null);
        assertThat(issued.statusCode()).isEqualTo(201);
        assertThat(issued.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        JsonNode body = read(issued);
        String token = body.get("token").asString();
        assertThat(body.get("mcp_url").asString()).endsWith("/mcp");
        assertThat(body.get("claude_md_snippet").asString()).contains("validate_task_plan");
        assertThat(body.get("token_info").has("label")).isFalse();
        String tokenId = body.get("token_info").get("id").asString();

        String hash = jdbc.queryForObject("SELECT token_hash FROM agent_token WHERE id = ?", String.class,
                Long.parseLong(tokenId));
        assertThat(hash).isNotEqualTo(token).doesNotContain(token);

        assertThat(mcp(token).statusCode()).isEqualTo(404);

        HttpResponse<String> list = leader.get("/api/me/agent-tokens");
        assertThat(list.body()).doesNotContain(token).doesNotContain(hash);
        JsonNode item = read(list).get("items").get(0);
        assertThat(item.get("id").asString()).isEqualTo(tokenId);
        assertThat(item.has("last_used_at")).isTrue();
    }

    /** 깨지면: 폐기한 토큰이 계속 MCP 를 부르거나, 남의 토큰을 폐기할 수 있다 (Jira 완료 조건 1, AGT-A03). */
    @Test
    void revokedToken_isRejectedOnTheNextRequest() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        JsonNode issued = read(leader.post(agentTokens(p.projectId()), "{\"label\":\" 개발 PC \"}"));
        String token = issued.get("token").asString();
        String tokenId = issued.get("token_info").get("id").asString();
        assertThat(issued.get("token_info").get("label").asString()).isEqualTo("개발 PC");
        assertThat(mcp(token).statusCode()).isEqualTo(404);

        // 남의 토큰은 없는 토큰과 같은 404 이고 폐기되지 않는다
        ApiClient other = client().login(fixtures().emailOf(fixtures().account()), AccessFixtures.PASSWORD);
        assertThat(other.send(other.request("/api/agent-tokens/" + tokenId).DELETE()).statusCode()).isEqualTo(404);
        assertThat(mcp(token).statusCode()).isEqualTo(404);

        assertThat(leader.send(leader.request("/api/agent-tokens/" + tokenId).DELETE()).statusCode()).isEqualTo(204);
        HttpResponse<String> after = mcp(token);
        assertThat(after.statusCode()).isEqualTo(401);
        assertThat(after.body()).contains("\"code\":\"token_revoked\"");
        // 이미 폐기된 본인 토큰도 204 (명세)
        assertThat(leader.send(leader.request("/api/agent-tokens/" + tokenId).DELETE()).statusCode()).isEqualTo(204);
        assertThat(read(leader.get("/api/me/agent-tokens")).get("items").get(0).has("revoked_at")).isTrue();
    }

    /** 깨지면: 제외된 팀원·삭제된 프로젝트의 토큰이 살아 있거나, 아무 문자열이나 토큰으로 통한다 (-172 참고 2, ACC-05). */
    @Test
    void endedMembership_archivedProject_unknownToken_areRejected() {
        var p = fixtures().projectWithLeader();
        long bob = fixtures().account();
        long bobMember = fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient bobClient = client().login(fixtures().emailOf(bob), AccessFixtures.PASSWORD);
        String bobToken = read(bobClient.post(agentTokens(p.projectId()), "{}")).get("token").asString();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String leaderToken = read(leader.post(agentTokens(p.projectId()), "{}")).get("token").asString();

        fixtures().removeMember(bobMember);
        HttpResponse<String> removed = mcp(bobToken);
        assertThat(removed.statusCode()).isEqualTo(401);
        assertThat(removed.body()).contains("\"code\":\"token_revoked\"");
        // 나간 프로젝트에는 새로 발급할 수 없다
        assertThat(bobClient.post(agentTokens(p.projectId()), "{}").statusCode()).isEqualTo(404);

        fixtures().archiveProject(p.projectId());
        assertThat(mcp(leaderToken).statusCode()).isEqualTo(401);

        HttpResponse<String> unknown = mcp("no-such-token");
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(unknown.body()).contains("\"code\":\"unauthenticated\"");
    }

    /** 깨지면: 토큰이 한도 없이 쌓이거나, 폐기해도 다시 발급할 수 없다 (Q06 프로젝트별 한 계정당 5개). */
    @Test
    void activeTokenLimit_countsOnlyUnrevokedTokens() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        String first = null;
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> res = leader.post(agentTokens(p.projectId()), "{}");
            assertThat(res.statusCode()).isEqualTo(201);
            if (first == null) {
                first = read(res).get("token_info").get("id").asString();
            }
        }
        HttpResponse<String> over = leader.post(agentTokens(p.projectId()), "{}");
        assertThat(over.statusCode()).isEqualTo(409);
        assertThat(over.body()).contains("agent_limit_reached");

        // 다른 프로젝트 한도는 따로 센다
        long other = fixtures().project(p.leaderAccountId());
        fixtures().member(other, p.leaderAccountId(), ProjectRole.LEADER);
        assertThat(leader.post(agentTokens(other), "{}").statusCode()).isEqualTo(201);
        assertThat(read(leader.get("/api/me/agent-tokens?project_id=" + other)).get("items").size()).isEqualTo(1);

        leader.send(leader.request("/api/agent-tokens/" + first).DELETE());
        assertThat(leader.post(agentTokens(p.projectId()), "{}").statusCode()).isEqualTo(201);
    }

    /** 깨지면: 동시에 발급하면 한도(Q06)를 넘는다 — 멤버십 행 잠금이 빠졌다. */
    @Test
    void concurrentIssue_doesNotExceedLimit() throws Exception {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        for (int i = 0; i < 4; i++) {
            assertThat(leader.post(agentTokens(p.projectId()), "{}").statusCode()).isEqualTo(201);
        }
        int racers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            List<CompletableFuture<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return leader.post(agentTokens(p.projectId()), "{}").statusCode();
                }, pool));
            }
            start.countDown();
            List<Integer> statuses = calls.stream().map(CompletableFuture::join).toList();
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_token WHERE project_id = ? AND revoked_at IS NULL",
                Long.class, p.projectId())).isEqualTo(5);
    }

    /** 깨지면: 남의 토큰이 내 목록에 보이거나, 볼 수 없는 프로젝트 필터가 오류로 존재를 드러낸다 (AGT-A02, -172 합의 3). */
    @Test
    void list_showsOnlyMine_andFilterIsSilent() {
        var p = fixtures().projectWithLeader();
        ApiClient leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
        leader.post(agentTokens(p.projectId()), "{}");
        leader.post(agentTokens(p.projectId()), "{}");
        long bob = fixtures().account();
        fixtures().member(p.projectId(), bob, ProjectRole.MEMBER);
        ApiClient bobClient = client().login(fixtures().emailOf(bob), AccessFixtures.PASSWORD);
        bobClient.post(agentTokens(p.projectId()), "{}");

        JsonNode firstPage = read(leader.get("/api/me/agent-tokens?limit=1"));
        assertThat(firstPage.get("items").size()).isEqualTo(1);
        assertThat(firstPage.get("has_more").asBoolean()).isTrue();
        JsonNode secondPage = read(leader.get("/api/me/agent-tokens?limit=1&cursor="
                + firstPage.get("next_cursor").asString()));
        assertThat(secondPage.get("items").size()).isEqualTo(1);
        assertThat(secondPage.get("has_more").asBoolean()).isFalse();

        assertThat(read(bobClient.get("/api/me/agent-tokens")).get("items").size()).isEqualTo(1);
        long theirs = fixtures().projectWithLeader().projectId();
        assertThat(read(leader.get("/api/me/agent-tokens?project_id=" + theirs)).get("items").size()).isZero();
        assertThat(leader.get("/api/me/agent-tokens?project_id=abc").statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> mcp(String token) {
        return client().get(MCP_PROBE, "Authorization", "Bearer " + token);
    }

    private static String agentTokens(long projectId) {
        return "/api/projects/" + projectId + "/agent-tokens";
    }

    private JsonNode read(HttpResponse<String> res) {
        return json.readTree(res.body());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
