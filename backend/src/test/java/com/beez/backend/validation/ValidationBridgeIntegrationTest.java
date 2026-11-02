package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.service.ValidationAcceptService;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

/** Actual DB and unified worker; search and AI are controlled test doubles. */
class ValidationBridgeIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @Autowired ValidationAcceptService acceptance;
    @MockitoBean HybridDocumentCandidateSearch search;
    @MockitoBean ValidationAnalyzer analyzer;
    private TaskApiSupport api;
    private long project, owner, baseline;

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); owner = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p")
                .param("p", project).query(Long.class).single();
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false));
    }

    @Test void acceptedJobUsesExistingRunAndAppliesIncompleteAiResultSafely() throws Exception {
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("회원 확인", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        long version = TaskApiSupport.longField(task.body(), "lock_version");
        String path = "/api/projects/" + project + "/tasks/" + taskId + "/validations";
        String body = "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":" + version + "}";
        var accepted = api.postWithHeaders(path, body, owner, "Idempotency-Key", "bridge-test");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = JSON.readTree(accepted.body()).get("id").asLong();
        assertThat(api.jdbc.sql("select count(*) from validation where task_id=:t")
                .param("t", taskId).query(Integer.class).single()).isEqualTo(1);
        AtomicReference<Map<String, Object>> sent = new AtomicReference<>();
        when(analyzer.analyze(any())).thenAnswer(call -> {
            Map<String, Object> request = call.getArgument(0);
            sent.set(request);
            List<Map<String, Object>> unassessed = new ArrayList<>();
            for (Object check : (List<?>) request.get("checks")) {
                unassessed.add(Map.of("check_key", ((Map<?, ?>) check).get("key"),
                        "reason", "EVIDENCE_INSUFFICIENT", "message", "근거 부족"));
            }
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                    "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                    "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 10)),
                    "result", Map.of("analysis_status", "PARTIAL", "checked_keys", List.of(),
                            "unassessed", unassessed, "findings", List.of()));
        });

        assertThat(worker.runOnce()).isTrue();
        assertThat(sent.get()).as("AI analyzer was invoked after approved context assembly").isNotNull();
        assertThat(api.jdbc.sql("select count(*) from validation_run where validation_id=:v")
                .param("v", validation).query(Integer.class).single()).isEqualTo(1);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("HUMAN_REVIEW");
        assertThat(api.jdbc.sql("select usage::text from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single())
                .contains("llm-validate-" + validation + "-attempt-1-call-1");
    }

    @Test void truncatedSearchClosesRunForHumanReviewWithoutCallingAi() throws Exception {
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), true, false));
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("검색 범위 초과", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        var accepted = api.post("/api/projects/" + project + "/tasks/" + taskId + "/validations",
                "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":"
                        + TaskApiSupport.longField(task.body(), "lock_version") + "}", owner);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = JSON.readTree(accepted.body()).get("id").asLong();

        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(analyzer);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select resolution from validation_finding where validation_id=:v and kind='ERROR'")
                .param("v", validation).query(String.class).single()).isEqualTo("HUMAN_REVIEW");
        assertThat(api.jdbc.sql("select usage::text from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).optional()).isEmpty();
    }

    private long[] queuedTask() {
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("검증 요청자 확인", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", taskId).query(Long.class).single();
        return new long[] { taskId, revision, TaskApiSupport.longField(task.body(), "lock_version") };
    }

    private void assertClosedWithoutSearchOrAi(long validation) {
        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(search, analyzer);
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("ERROR");
    }

    @Test void removedMemberCannotSearchOrCallAiFromQueuedWork() {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        api.jdbc.sql("update project_member set active=false where project_id=:p and account_id=:a")
                .param("p", project).param("a", owner).update();
        assertClosedWithoutSearchOrAi(validation);
    }

    @Test void revokedAgentCannotSearchOrCallAiFromQueuedWork() {
        long[] task = queuedTask();
        long token = api.agentTokenId();
        var accepted = acceptance.accept(CallerContext.agent(owner, token), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        api.jdbc.sql("update agent_token set revoked_at=now() where id=:id").param("id", token).update();
        assertClosedWithoutSearchOrAi(validation);
    }

    @Test void oldQueueWithoutRequesterCannotSearchOrCallAi() {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        api.jdbc.sql("update validation set summary=summary - 'request_actor' where id=:id")
                .param("id", validation).update();
        assertClosedWithoutSearchOrAi(validation);
    }

    @Test void relatedTaskAddedDuringAiCallMakesResultStale() throws Exception {
        long[] task = queuedTask();
        var accepted = acceptance.accept(CallerContext.human(owner), project, task[0], task[1], (int) task[2]);
        assertThat(accepted.finished()).isFalse();
        long validation = Long.parseLong(accepted.validation().id());
        when(analyzer.analyze(any())).thenAnswer(call -> {
            var related = api.post("/api/projects/" + project + "/tasks",
                    api.planJson("새 관련 작업", api.readsRef("API-01"), "[]"), owner);
            assertThat(related.statusCode()).as(related.body()).isEqualTo(201);
            return Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(),
                    "result", Map.of("analysis_status", "COMPLETE", "checked_keys", List.of(),
                            "unassessed", List.of(), "findings", List.of()));
        });
        assertThat(worker.runOnce()).isTrue();
        verify(analyzer).analyze(any());
        assertThat(api.jdbc.sql("select status from validation where id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("STALE");
        assertThat(api.jdbc.sql("select status from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).isEqualTo("DISCARDED");
    }

}
