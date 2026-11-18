package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.beez.backend.ai.AiClient;
import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.task.service.StartGate;
import com.beez.backend.validation.service.FakeValidationAnalyzer;
import com.beez.backend.validation.service.ValidationAnalyzer;
import com.beez.backend.validation.worker.ValidationWorker;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

/** Fake development mode must not need a search index, embeddings, or an AI server. */
@TestPropertySource(properties = "beez.validation.fake-analyzer=true")
class ValidationFakeModeIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @Autowired ValidationAnalyzer analyzer;
    @Autowired StartGate startGate;
    @MockitoBean HybridDocumentCandidateSearch search;
    @MockitoBean AiClient ai;
    private TaskApiSupport api;
    private long project;
    private long owner;

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        owner = api.accountId("member@test");
    }

    @Test void fakeModeRunsWithoutSearchOrAiAndProductionGateRejectsItsPass() {
        assertThat(analyzer).isInstanceOf(FakeValidationAnalyzer.class);
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("가짜 분석 테스트", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:id")
                .param("id", taskId).query(Long.class).single();
        long lockVersion = TaskApiSupport.longField(task.body(), "lock_version");
        var accepted = api.post("/api/projects/" + project + "/tasks/" + taskId + "/validations",
                "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":" + lockVersion + "}", owner);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = TaskApiSupport.longField(accepted.body(), "id");

        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(search, ai);
        assertThat(api.jdbc.sql("select status from validation where id=:id").param("id", validation)
                .query(String.class).single()).isEqualTo("PASS");
        assertThat(api.jdbc.sql("select analyzer_version from validation_run where validation_id=:id")
                .param("id", validation).query(String.class).single()).isEqualTo(FakeValidationAnalyzer.MODEL);
        assertThat(api.jdbc.sql("select count(*) from llm_usage where validation_run_id in "
                + "(select id from validation_run where validation_id=:id)")
                .param("id", validation).query(Integer.class).single()).isZero();

        String renamedPlan = api.planJson("가짜 분석 테스트 이름 변경", api.implementsRef("API-01"), "[]").trim();
        String revisionBody = renamedPlan.substring(0, renamedPlan.lastIndexOf('}'))
                + ",\"expected_revision_no\":1,\"expected_lock_version\":" + lockVersion + "}";
        var renamed = api.post("/api/projects/" + project + "/tasks/" + taskId + "/plan-revisions", revisionBody, owner);
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(201);
        long renamedRevision = api.jdbc.sql("select current_plan_revision_id from task where id=:id")
                .param("id", taskId).query(Long.class).single();
        long copy = api.jdbc.sql("select max(id) from validation where plan_revision_id=:revision")
                .param("revision", renamedRevision).query(Long.class).single();
        long newLockVersion = TaskApiSupport.longField(renamed.body(), "lock_version");
        assertThat(api.jdbc.sql("select count(*) from validation_run where validation_id=:id")
                .param("id", copy).query(Integer.class).single()).isZero();

        // A carried copy has no run of its own; production must inspect the original fake run.
        ReflectionTestUtils.setField(startGate, "fakeAnalyzerEnabled", false);
        try {
            for (long given : new long[] {validation, copy}) {
                var started = api.post("/api/projects/" + project + "/tasks/" + taskId + "/state-events",
                        "{\"event\":\"START\",\"plan_revision_id\":\"" + renamedRevision
                                + "\",\"expected_lock_version\":" + newLockVersion
                                + ",\"validation_id\":\"" + given + "\"}", owner);
                assertThat(started.statusCode()).as(started.body()).isEqualTo(409);
                assertThat(started.body()).contains("가짜 분석기");
            }
        } finally {
            ReflectionTestUtils.setField(startGate, "fakeAnalyzerEnabled", true);
        }
        var started = api.post("/api/projects/" + project + "/tasks/" + taskId + "/state-events",
                "{\"event\":\"START\",\"plan_revision_id\":\"" + renamedRevision
                        + "\",\"expected_lock_version\":" + newLockVersion
                        + ",\"validation_id\":\"" + validation + "\"}", owner);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
    }

    @Test void fakeModeCompletesDeltaWithoutSearchOrAi() {
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("완료 검증 테스트", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:id")
                .param("id", taskId).query(Long.class).single();
        long pass = api.passValidation(taskId);
        String path = "/api/projects/" + project + "/tasks/" + taskId + "/state-events";
        var start = api.post(path, "{\"event\":\"START\",\"plan_revision_id\":\"" + revision
                + "\",\"expected_lock_version\":" + TaskApiSupport.longField(task.body(), "lock_version")
                + ",\"validation_id\":\"" + pass + "\"}", owner);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(200);
        var done = api.post(path, "{\"event\":\"COMPLETED\",\"plan_revision_id\":\"" + revision
                + "\",\"expected_lock_version\":" + TaskApiSupport.longField(start.body(), "lock_version")
                + ",\"delta\":[{\"kind\":\"CHANGE\",\"text\":\"완료 내용 변경\"}]}", owner);
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long validation = TaskApiSupport.longField(done.body(), "delta_validation_id");

        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(search, ai);
        assertThat(api.jdbc.sql("select status from validation where id=:id").param("id", validation)
                .query(String.class).single()).isEqualTo("PASS");
        assertThat(api.jdbc.sql("select analyzer_version from validation_run where validation_id=:id")
                .param("id", validation).query(String.class).single()).isEqualTo(FakeValidationAnalyzer.MODEL);
        assertThat(api.jdbc.sql("select status from task where id=:id").param("id", taskId)
                .query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test void fakeModeHandlesExistingQueueWithoutStoredRequester() {
        var task = api.post("/api/projects/" + project + "/tasks",
                api.planJson("이전 요청 테스트", api.implementsRef("API-01"), "[]"), owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:id")
                .param("id", taskId).query(Long.class).single();
        var accepted = api.post("/api/projects/" + project + "/tasks/" + taskId + "/validations",
                "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":"
                        + TaskApiSupport.longField(task.body(), "lock_version") + "}", owner);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = TaskApiSupport.longField(accepted.body(), "id");
        api.jdbc.sql("update validation set summary=summary - 'request_actor' where id=:id")
                .param("id", validation).update();

        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(search, ai);
        assertThat(api.jdbc.sql("select status from validation where id=:id").param("id", validation)
                .query(String.class).single()).isEqualTo("PASS");
    }
}
