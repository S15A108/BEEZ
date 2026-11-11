package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class TaskBreakdownIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @MockitoBean AiClient ai;

    TaskApiSupport api;
    long projectId;
    long baselineId;
    long leader;
    long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        api.jdbc.sql("update project_member set area = 'backend' where account_id = :a").param("a", leader).update();
        api.jdbc.sql("update project_member set role_label = 'Frontend' where account_id = :a").param("a", member).update();
    }

    @Test
    void createsAssignedTasksAndDependenciesOnceAndReplaysAfterPublishing() {
        addApprovedDocument();
        var backend = draft("api", "API-01", "backend", List.of());
        var frontend = draft("ui", "API-01", "Frontend", List.of("api"));
        var unassigned = draft("follow-up", "API-02", null, List.of());
        when(ai.taskBreakdown(anyMap())).thenAnswer(call -> {
            Map<String, Object> input = call.getArgument(0);
            assertThat(input.get("assignment_areas")).isEqualTo(List.of("Frontend", "backend"));
            assertThat(input.get("documents").toString()).contains("Approved backlog requirements");
            return reply(frontend, backend, unassigned);
        });

        var created = generate(leader);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        var result = json.readTree(created.body());
        assertThat(result.path("baseline_id").asString()).isEqualTo(Long.toString(baselineId));
        assertThat(result.path("tasks").size()).isEqualTo(3);
        var first = result.path("tasks").get(0);
        var second = result.path("tasks").get(1);
        assertThat(first.path("title").asString()).isEqualTo("api");
        assertThat(first.path("assignee_member_id").asString()).isEqualTo(api.memberId("leader@test").toString());
        assertThat(second.path("assignee_member_id").asString()).isEqualTo(api.memberId("member@test").toString());
        assertThat(result.path("tasks").get(2).path("assignee_member_id").isMissingNode()).isTrue();
        assertThat(api.jdbc.sql("select depends_on_task_id from task_dependency where task_id = :id")
                .param("id", second.path("id").asLong()).query(Long.class).single()).isEqualTo(first.path("id").asLong());
        assertThat(api.jdbc.sql("select count(*) from notification where type = 'TASK_ASSIGNED' and recipient_account_id = :id")
                .param("id", member).query(Long.class).single()).isEqualTo(1);
        var mine = api.get("/api/projects/" + projectId + "/tasks?assignee_member_id=" + api.memberId("member@test"), member);
        var listed = body(mine, 200).path("items").get(0);
        assertThat(listed.path("id").asString()).isEqualTo(second.path("id").asString());
        var ref = listed.path("current_plan_revision").path("plan").path("design_refs").get(0);
        assertThat(ref.path("design_item_id").asLong()).isEqualTo(api.designItemId("API-01"));
        assertThat(ref.path("design_item_version_id").asLong()).isEqualTo(api.currentVersionId("API-01"));

        api.publish(2, Map.of("API-01", api.currentVersionId("API-01"), "API-02", api.currentVersionId("API-02")));
        var replay = generate(leader);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body())).isEqualTo(result);
        assertThat(count("task")).isEqualTo(3);
        verify(ai).taskBreakdown(anyMap());
    }

    @Test
    void aConflictBetweenTwoGeneratedTasksUsesTheExistingStartGateAndMeeting() {
        when(ai.taskBreakdown(anyMap())).thenReturn(reply(
                draft("api", "API-01", "backend", List.of()),
                draft("ui", "API-01", "Frontend", List.of())));
        var tasks = body(generate(leader), 200).path("tasks");
        JsonNode backend = reviseForContractChange(tasks.get(0), leader);
        JsonNode frontend = reviseForContractChange(tasks.get(1), member);
        long pass = api.passValidation(backend.path("id").asLong());
        assertThat(start(backend, pass, leader).statusCode()).isEqualTo(200);

        var validation = body(api.post(taskPath(frontend) + "/validations", """
                {"plan_revision_id":"%s","expected_lock_version":%d}
                """.formatted(frontend.path("current_plan_revision").path("id").asString(), frontend.path("lock_version").asInt()),
                member), 200);
        assertThat(validation.path("status").asString()).isEqualTo("NEEDS_REVIEW");
        var finding = validation.path("findings").get(0);
        assertThat(finding.path("kind").asString()).isEqualTo("CONFLICT");
        assertThat(finding.path("related_task_id").asString()).isEqualTo(backend.path("id").asString());
        assertThat(start(frontend, validation.path("id").asLong(), member).statusCode()).isEqualTo(409);
        assertThat(count("meeting")).isEqualTo(1);
    }

    @Test
    void requiresHumanLeaderBeforeCallingAi() {
        assertThat(generate(member).statusCode()).isEqualTo(403);
        verifyNoInteractions(ai);
    }

    @Test
    void rejectsInvalidModelReferencesBeforeWriting() {
        Map<String, Object> badTask = draft("api", "API-01", "backend", List.of());
        badTask.put("design_refs", List.of(Map.of("design_item_id", api.designItemId("API-01").toString(),
                "design_item_version_id", "999999", "relation", "IMPLEMENTS")));
        when(ai.taskBreakdown(anyMap())).thenReturn(reply(badTask));

        var response = generate(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(502);
        assertThat(response.body()).contains("ai_invalid_output");
        assertThat(count("task")).isZero();
        assertThat(count("task_breakdown_batch")).isZero();
    }

    @Test
    void mapsAnUnusableAiBatchToTheInvalidOutputError() {
        byte[] error = """
                {"error":{"code":"invalid_output","message":"The LLM request could not be completed."}}
                """.getBytes(StandardCharsets.UTF_8);
        when(ai.taskBreakdown(anyMap())).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY, error, StandardCharsets.UTF_8));

        var response = body(generate(leader), 502);

        assertThat(response.path("error").path("code").asString()).isEqualTo("ai_invalid_output");
        assertThat(count("task_breakdown_batch")).isZero();
    }

    @Test
    void persistsTheValidDependencyChainWithoutInvalidTasksOrTheirDependents() {
        var invalid = draft("invalid", "API-01", "backend", List.of());
        invalid.put("design_refs", List.of(Map.of("design_item_id", api.designItemId("API-01").toString(),
                "design_item_version_id", "999999", "relation", "IMPLEMENTS")));
        var blocked = draft("blocked", "API-01", "Frontend", List.of("invalid"));
        var indirectlyBlocked = draft("indirectly-blocked", "API-01", null, List.of("blocked"));
        var backend = draft("api", "API-02", "backend", List.of());
        var frontend = draft("ui", "API-02", "Frontend", List.of("api"));
        when(ai.taskBreakdown(anyMap())).thenReturn(reply(indirectlyBlocked, frontend, invalid, blocked, backend));

        var tasks = body(generate(leader), 200).path("tasks");

        assertThat(tasks.size()).isEqualTo(2);
        assertThat(tasks.get(0).path("title").asString()).isEqualTo("api");
        assertThat(tasks.get(1).path("title").asString()).isEqualTo("ui");
        assertThat(api.jdbc.sql("select depends_on_task_id from task_dependency where task_id = :id")
                .param("id", tasks.get(1).path("id").asLong()).query(Long.class).single())
                .isEqualTo(tasks.get(0).path("id").asLong());
        assertThat(count("task")).isEqualTo(2);
    }

    @Test
    void rollsBackEarlierTasksIfALaterPlanFailsToSave() {
        var backend = draft("api", "API-01", "backend", List.of());
        var frontend = draft("ui", "API-02", "Frontend", List.of("api"));
        when(ai.taskBreakdown(anyMap())).thenAnswer(call -> {
            api.jdbc.sql("update design_item set retired_at = now() where id = :id")
                    .param("id", api.designItemId("API-02")).update();
            return reply(backend, frontend);
        });

        var response = generate(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(count("task")).isZero();
        assertThat(count("task_breakdown_batch")).isZero();
    }

    private HttpResponse<String> generate(long caller) {
        return api.post("/api/projects/" + projectId + "/task-breakdown", "{}", caller);
    }

    private JsonNode reviseForContractChange(JsonNode generated, long caller) {
        JsonNode existing = body(api.get(taskPath(generated), caller), 200);
        var plan = (ObjectNode) json.readTree(api.planJson(existing.path("key").asString(), api.changesRef("API-01"), "[]"));
        plan.put("no_contract_change", false);
        plan.put("expected_revision_no", existing.path("current_plan_revision").path("revision_no").asInt());
        plan.put("expected_lock_version", existing.path("lock_version").asInt());
        return body(api.post(taskPath(generated) + "/plan-revisions", json.writeValueAsString(plan), caller), 201);
    }

    private HttpResponse<String> start(JsonNode task, long validationId, long caller) {
        return api.post(taskPath(task) + "/state-events", """
                {"event":"START","plan_revision_id":"%s","expected_lock_version":%d,"validation_id":"%d"}
                """.formatted(task.path("current_plan_revision").path("id").asString(), task.path("lock_version").asInt(), validationId), caller);
    }

    private String taskPath(JsonNode task) {
        return "/api/projects/" + projectId + "/tasks/" + task.path("id").asString();
    }

    private JsonNode body(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private long count(String table) {
        return api.jdbc.sql("select count(*) from " + table + " where project_id = :p")
                .param("p", projectId).query(Long.class).single();
    }

    private void addApprovedDocument() {
        long document = api.jdbc.sql("""
                insert into document(project_id, type, title, created_by) values (:p, 'PLAN', 'Plan', :leader) returning id
                """).param("p", projectId).param("leader", leader).query(Long.class).single();
        long version = api.jdbc.sql("""
                insert into document_version(document_id, version_no, status, storage_key, content_hash, content_text, approved_at)
                values (:d, 1, 'APPROVED', 'plan', 'fixture', 'Approved backlog requirements', now()) returning id
                """).param("d", document).query(Long.class).single();
        api.jdbc.sql("""
                insert into baseline_document(baseline_id, project_id, document_id, document_version_id)
                values (:b, :p, :d, :v)
                """).param("b", baselineId).param("p", projectId).param("d", document).param("v", version).update();
    }

    private Map<String, Object> draft(String key, String design, String area, List<String> dependencies) {
        Map<String, Object> task = new LinkedHashMap<>(Map.of(
                "key", key, "title", key, "goal", "Implement approved design", "approach", "Follow the contract",
                "included_scope", List.of("feature"), "excluded_scope", List.of(), "completion_criteria", List.of("Tests pass"),
                "dependency_keys", dependencies, "design_refs", List.of(Map.of("relation", "IMPLEMENTS",
                        "design_item_id", api.designItemId(design).toString(), "design_item_version_id", api.currentVersionId(design).toString()))));
        task.put("assignment_area", area);
        return task;
    }

    private static Map<String, Object> reply(Object... drafts) {
        return Map.of("origin", "MODEL", "model", "test", "usage", List.of(), "result", Map.of("tasks", List.of(drafts)));
    }
}
