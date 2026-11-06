package com.beez.backend.bee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP·Postgres와 AI 대역으로 권한, 스냅샷, 변경 감지를 검증한다. */
class AssignmentSuggestionIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGIN = "http://localhost:5173";
    @Autowired JdbcTemplate db;
    @MockitoBean AiClient ai;
    private AccessFixtures.ProjectWithLeader project;
    private ApiClient leader;
    private long memberId;
    private String taskId;

    @BeforeEach void setUp() {
        project = fixtures().projectWithLeader();
        long account = fixtures().account();
        memberId = fixtures().member(project.projectId(), account, ProjectRole.MEMBER);
        db.update("update project_member set area = 'backend', role_label = 'Backend' where id = ?", project.leaderMemberId());
        db.update("update project_member set area = 'frontend', role_label = 'Frontend' where id = ?", memberId);
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        String plan = """
                {"title":"Login API","goal":"Implement login","included_scope":["API"],"excluded_scope":[],
                 "approach":"Use contract","completion_criteria":["Works"],"dependency_task_ids":[],
                 "design_refs":[],"unlinked":[],"no_contract_change":true}
                """;
        var created = leader.post("/api/projects/" + project.projectId() + "/tasks", plan);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        taskId = JSON.readTree(created.body()).path("id").asString();
    }

    private String path() { return "/api/projects/" + project.projectId() + "/assignment-suggestions"; }
    private HttpResponse<String> suggest(ApiClient caller) {
        return caller.post(path(), "{\"task_ids\":[\"" + taskId + "\"]}", "Origin", ORIGIN);
    }
    private Map<String, Object> reply() {
        return Map.of("origin", "MODEL", "model", "fixture", "usage", List.of(), "result", Map.of(
                "suggestions", List.of(Map.of("task_id", taskId, "member_id", String.valueOf(project.leaderMemberId()),
                        "reason", "Backend area", "alternatives", List.of(Map.of("member_id", String.valueOf(memberId),
                                "reason", "Alternative area")))), "unresolved", List.of()));
    }

    private long draft(Long assignee) {
        return db.queryForObject("""
                insert into task(project_id, key, title, assignee_member_id, status)
                values (?, 'T-DRAFT', 'Draft task', ?, 'DRAFT') returning id
                """, Long.class, project.projectId(), assignee);
    }

    @Test void realDatabaseSnapshotAndResultLeaveTheTaskUnchanged() {
        AtomicReference<Map<String, Object>> input = new AtomicReference<>();
        when(ai.assignmentRecommendations(anyMap())).thenAnswer(invocation -> {
            input.set(invocation.getArgument(0));
            return reply();
        });
        var before = db.queryForMap("select assignee_member_id, status, lock_version from task where id = ?", Long.valueOf(taskId));
        var response = suggest(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).path("suggestions").get(0).path("alternatives").size()).isEqualTo(1);
        assertThat(input.get()).containsEntry("current_tasks_complete", true);
        assertThat((List<?>) input.get().get("members")).hasSize(2);
        assertThat((List<?>) input.get().get("current_tasks")).hasSize(1);
        assertThat(input.get().toString()).contains("backend", "frontend").doesNotContain(project.leaderEmail());
        assertThat(db.queryForMap("select assignee_member_id, status, lock_version from task where id = ?", Long.valueOf(taskId)))
                .isEqualTo(before);
    }

    @Test void changesDuringTheModelCallDiscardTheRecommendation() {
        when(ai.assignmentRecommendations(anyMap())).thenAnswer(invocation -> {
            db.update("update project_member set area = 'changed' where id = ?", memberId);
            return reply();
        });
        var response = suggest(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("recommendation_stale");
    }

    @Test void assignedDraftIsIncludedInTheCompleteWorkload() {
        long draftId = draft(memberId);
        AtomicReference<Map<String, Object>> input = new AtomicReference<>();
        when(ai.assignmentRecommendations(anyMap())).thenAnswer(invocation -> {
            input.set(invocation.getArgument(0));
            return reply();
        });
        var response = suggest(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(input.get()).containsEntry("current_tasks_complete", true);
        List<?> current = (List<?>) input.get().get("current_tasks");
        assertThat(current).hasSize(2);
        assertThat(current).anySatisfy(row -> {
            Map<?, ?> task = (Map<?, ?>) row;
            assertThat(task.get("id")).isEqualTo(String.valueOf(draftId));
            assertThat(task.get("status")).isEqualTo("DRAFT");
            assertThat(task.get("assignee_member_id")).isEqualTo(String.valueOf(memberId));
        });
    }

    @Test void lockVersionOnlyChangesDoNotDiscardTheRecommendation() {
        long draftId = draft(memberId);
        when(ai.assignmentRecommendations(anyMap())).thenAnswer(invocation -> {
            db.update("update task set lock_version = lock_version + 1 where id in (?, ?)", Long.valueOf(taskId), draftId);
            return reply();
        });
        var response = suggest(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @Test void assigneeChangedDuringTheModelCallDiscardsTheRecommendation() {
        when(ai.assignmentRecommendations(anyMap())).thenAnswer(invocation -> {
            db.update("update task set assignee_member_id = ?, lock_version = lock_version + 1 where id = ?",
                    memberId, Long.valueOf(taskId));
            return reply();
        });
        var response = suggest(leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("recommendation_stale");
    }

    @Test void untrustedRefererIsRejectedWithTheOriginErrorCode() {
        var response = leader.post(path(), "{\"task_ids\":[\"" + taskId + "\"]}", "Referer", "https://untrusted.test/");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("request_origin_rejected");
        verifyNoInteractions(ai);
    }

    @Test void nonMemberAndUnknownTaskAreNotSentToAi() {
        String email = AccessFixtures.uniqueEmail();
        fixtures().account(email);
        ApiClient outsider = client().login(email, AccessFixtures.PASSWORD);
        assertThat(suggest(outsider).statusCode()).isEqualTo(404);
        var missing = leader.post(path(), "{\"task_ids\":[\"999999999\"]}", "Origin", ORIGIN);
        assertThat(missing.statusCode()).isEqualTo(404);
        verifyNoInteractions(ai);
    }
}
