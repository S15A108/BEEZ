package com.beez.backend.designitem;

import static org.assertj.core.api.Assertions.*;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.dto.CreateDesignItemRequest;
import com.beez.backend.designitem.dto.DesignItemResponse;
import com.beez.backend.designitem.service.DesignItemCommandService;
import com.beez.backend.designitem.service.DesignItemQueryService;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

@TestPropertySource(properties = {"beez.cors.allowed-origins=https://app.beez.test", "beez.document.embedding-profile=design-test"})
class DesignItemApiIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build();
    private static final String ORIGIN = "https://app.beez.test";
    @Autowired JdbcTemplate jdbc;
    @Autowired DesignItemCommandService commands;
    @Autowired DesignItemQueryService queries;
    @Autowired PlatformTransactionManager transactions;
    AccessFixtures.ProjectWithLeader p;
    CallerContext actor;
    ApiClient leader;

    @BeforeEach void setup() {
        p = fixtures().projectWithLeader();
        actor = CallerContext.human(p.leaderAccountId());
        leader = client().login(p.leaderEmail(), AccessFixtures.PASSWORD);
    }

    String path() { return "/api/projects/" + p.projectId() + "/design-items"; }
    JsonNode json(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }
    DesignItemResponse create(String description) {
        return commands.create(actor, p.projectId(), new CreateDesignItemRequest("REQUIREMENT", 1,
                Map.of("description", description), null, null), UUID.randomUUID().toString());
    }
    @Test void actualLoginCrudRetainsExtraFieldsDetectsStaleEditAndKeepsExcludedHistory() {
        String body = """
                {"kind":"REQUIREMENT","schema_version":1,"content":{"description":"로그인 사용자 조회","acceptance":["멤버만"]}}
                """;
        var created = json(leader.post(path(), body, "Origin", ORIGIN, "Idempotency-Key", "create"), 201);
        assertThat(created.path("key").asText()).isEqualTo("REQ-001");
        assertThat(created.path("source").isMissingNode()).isTrue();
        assertThat(created.path("content").path("acceptance").get(0).asText()).isEqualTo("멤버만");
        assertThat(json(leader.post(path(), body, "Origin", ORIGIN, "Idempotency-Key", "create"), 201)).isEqualTo(created);
        var before = json(leader.get(path() + "?status=DRAFT"), 200).path("draft_snapshot_hash").asText();
        String itemPath = "/api/human/design-items/" + created.path("design_item_id").asText();
        var update = Map.of("project_id", Long.toString(p.projectId()), "expected_version_id", created.path("id").asText(),
                "expected_content_hash", created.path("content_hash").asText(), "content", Map.of("description", "수정 내용"));
        var edited = json(leader.patch(itemPath, JSON.writeValueAsString(update), "Origin", ORIGIN), 200);
        assertThat(edited.path("id")).isEqualTo(created.path("id"));
        assertThat(edited.path("content_hash")).isNotEqualTo(created.path("content_hash"));
        assertThat(json(leader.patch(itemPath, JSON.writeValueAsString(update), "Origin", ORIGIN), 409).path("error").path("code").asText())
                .isEqualTo("version_conflict");
        assertThat(json(leader.get(path() + "?status=DRAFT"), 200).path("draft_snapshot_hash").asText()).isNotEqualTo(before);
        String query = "?project_id=" + p.projectId() + "&expected_version_id=" + edited.path("id").asText()
                + "&expected_content_hash=" + edited.path("content_hash").asText();
        assertThat(leader.delete(itemPath + query, "", "Origin", ORIGIN).statusCode()).isEqualTo(204);
        assertThat(json(leader.get(path() + "?status=DRAFT"), 200).path("items").isEmpty()).isTrue();
        assertThat(json(leader.get(path() + "?status=REJECTED"), 200).path("items").get(0).path("id")).isEqualTo(created.path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_item_version WHERE id=?", Integer.class, edited.path("id").asLong())).isEqualTo(1);
    }

    @Test void allFiveKindsUseConfirmedV1AndRejectMalformedOrUnsupportedContent() {
        for (String kind : List.of("REQUIREMENT", "POLICY", "CONSTRAINT", "API_CONTRACT", "DATA_CONTRACT")) {
            var content = new HashMap<String, Object>(); content.put("description", "항목 " + kind);
            if (kind.equals("API_CONTRACT")) { content.put("method", "POST"); content.put("path", "/api/login"); }
            if (kind.equals("DATA_CONTRACT")) { content.put("entity", "account"); content.put("fields", List.of(Map.of("name", "id", "type", "bigint"))); }
            json(leader.post(path(), JSON.writeValueAsString(new CreateDesignItemRequest(kind, 1, content, null, null)),
                    "Origin", ORIGIN, "Idempotency-Key", kind), 201);
        }
        var invalid = List.of(new CreateDesignItemRequest("REQUIREMENT", 1, Map.of(), null, null),
                new CreateDesignItemRequest("POLICY", 2, Map.of("description", "x"), null, null),
                new CreateDesignItemRequest("API_CONTRACT", 1, Map.of("description", "x", "method", "post", "path", "/login"), null, null),
                new CreateDesignItemRequest("DATA_CONTRACT", 1, Map.of("description", "x", "entity", "account", "fields", "not-array"), null, null));
        for (var body : invalid) json(leader.post(path(), JSON.writeValueAsString(body), "Origin", ORIGIN, "Idempotency-Key", UUID.randomUUID().toString()), 400);
    }

    @Test void mutationRequiresTrustedOriginCurrentHumanLeaderAndSameProjectSource() {
        long member = fixtures().account();
        fixtures().member(p.projectId(), member, ProjectRole.MEMBER);
        var memberClient = client().login(fixtures().emailOf(member), AccessFixtures.PASSWORD);
        String body = JSON.writeValueAsString(new CreateDesignItemRequest("POLICY", 1, Map.of("description", "정책"), null, null));
        json(memberClient.post(path(), body, "Origin", ORIGIN, "Idempotency-Key", "member"), 403);
        json(leader.post(path(), body, "Idempotency-Key", "no-origin"), 403);
        json(client().post(path(), body, "Origin", ORIGIN, "Idempotency-Key", "anonymous"), 401);
        var foreign = fixtures().projectWithLeader();
        var foreignClient = client().login(foreign.leaderEmail(), AccessFixtures.PASSWORD);
        json(foreignClient.get(path() + "?status=DRAFT"), 404);
        long token = fixtures().agentToken(p.projectId(), p.leaderAccountId());
        assertThatThrownBy(() -> commands.create(CallerContext.agent(p.leaderAccountId(), token), p.projectId(),
                new CreateDesignItemRequest("POLICY", 1, Map.of("description", "정책"), null, null), "agent"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        var source = new CreateDesignItemRequest("POLICY", 1, Map.of("description", "정책"), Long.toString(Long.MAX_VALUE), "1절");
        json(leader.post(path(), JSON.writeValueAsString(source), "Origin", ORIGIN, "Idempotency-Key", "foreign-source"), 404);
        fixtures().removeMember(p.leaderMemberId());
        json(leader.post(path(), body, "Origin", ORIGIN, "Idempotency-Key", "removed"), 404);
    }

    @Test void concurrentCreatesDoNotDuplicateKeysOrIdempotentResults() throws Exception {
        var body = new CreateDesignItemRequest("REQUIREMENT", 1, Map.of("description", "동시 추가"), null, null);
        try (var pool = Executors.newFixedThreadPool(4)) {
            var gate = new CountDownLatch(1);
            var calls = new ArrayList<Future<DesignItemResponse>>();
            for (int i = 0; i < 4; i++) {
                String key = i < 2 ? "same" : "other-" + i;
                calls.add(pool.submit(() -> { gate.await(); return commands.create(actor, p.projectId(), body, key); }));
            }
            gate.countDown();
            var result = new ArrayList<DesignItemResponse>();
            for (var call : calls) result.add(call.get(20, TimeUnit.SECONDS));
            assertThat(result.get(0)).isEqualTo(result.get(1));
            assertThat(result.stream().map(DesignItemResponse::key).distinct()).hasSize(3);
        }
    }

    @Test void roleDemotedWhileWaitingForProjectLockCannotUseCachedLeaderMembership() throws Exception {
        var body = new CreateDesignItemRequest("POLICY", 1, Map.of("description", "권한 재검사"), null, null);
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<String> waiting = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", Long.class, p.projectId());
                var future = pool.submit(() -> {
                    try { commands.create(actor, p.projectId(), body, "demoted"); return "unexpected-success"; }
                    catch (ApiException e) { return e.getCode(); }
                });
                org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE 'SELECT id FROM project WHERE id=%'", Integer.class) > 0);
                jdbc.update("UPDATE project_member SET role='MEMBER' WHERE id=?", p.leaderMemberId());
                return future;
            });
            assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo("forbidden");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_item WHERE project_id=?", Integer.class, p.projectId())).isZero();
    }

}
