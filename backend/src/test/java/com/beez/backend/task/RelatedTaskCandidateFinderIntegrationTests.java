package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import com.beez.backend.validation.service.ValidationOutcomeWriter;
import com.beez.backend.validation.service.ValidationRequestService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RelatedTaskCandidateFinderIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired RelatedTaskCandidateFinder finder;
    @Autowired ValidationRequestService requests;
    @Autowired ValidationOutcomeWriter writer;
    private TaskApiSupport api;
    private long project;
    private long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        member = api.accountId("member@test");
    }

    private long create(String title, String reference, String dependencies) {
        var response = api.post("/api/projects/" + project + "/tasks",
                api.planJson(title, reference, dependencies), member);
        assertThat(response.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(response.body(), "id");
    }

    @Test
    void findsCurrentReferencesAndDependenciesButExcludesTargetAndClosedTasks() {
        long target = create("target", api.readsRef("API-01"), "[]");
        long shared = create("shared", api.readsRef("API-01"), "[]");
        long dependent = create("dependent", api.readsRef("API-02"), "[\"" + target + "\"]");
        long closed = create("closed", api.readsRef("API-01"), "[]");
        api.jdbc.sql("update task set status='CANCELLED',cancelled_at=now() where id=:id")
                .param("id", closed).update();
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:id")
                .param("id", target).query(Long.class).single();
        var result = finder.find(CallerContext.human(member), project, target, revision,
                List.of(api.designItemId("API-01")), 20);
        assertThat(result.candidates()).extracting(RelatedTaskCandidateFinder.Candidate::taskId)
                .containsExactly(shared, dependent);
        assertThat(result.candidates().getFirst().paths()).extracting(RelatedTaskCandidateFinder.Path::kind)
                .containsExactly("DESIGN_REFERENCE");
        assertThat(result.candidates().get(1).paths()).extracting(RelatedTaskCandidateFinder.Path::kind)
                .containsExactly("DEPENDENT_TASK");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void newRelatedTaskDuringModelCallDiscardsTheOldResult() {
        long target = create("target", api.readsRef("API-01"), "[]");
        var targetRow = api.jdbc.sql("select current_plan_revision_id,lock_version from task where id=:id")
                .param("id", target).query().singleRow();
        long revision = ((Number) targetRow.get("current_plan_revision_id")).longValue();
        int lock = ((Number) targetRow.get("lock_version")).intValue();
        long item = api.designItemId("API-01");
        long baseline = api.jdbc.sql("select current_baseline_id from project where id=:id")
                .param("id", project).query(Long.class).single();
        long version = api.jdbc.sql("select design_item_version_id from baseline_item where baseline_id=:baseline and design_item_id=:item")
                .param("baseline", baseline).param("item", item).query(Long.class).single();
        var caller = CallerContext.human(member);
        assertThat(finder.find(caller, project, target, revision, List.of(item), 20).candidates()).isEmpty();
        long validation = Long.parseLong(requests.start(caller, project, target, Long.toString(revision), lock,
                "freshness-integration").get("id").toString());
        UUID lease = UUID.randomUUID();
        api.jdbc.sql("update validation set input_snapshot_hash='sha256:fixture' where id=:id")
                .param("id", validation).update();
        api.jdbc.sql("update validation_job set status='LEASED',attempts=1,lease_token=:token,lease_until=now()+interval '1 minute' where validation_id=:id")
                .param("id", validation).param("token", lease).update();
        long run = api.jdbc.sql("insert into validation_run(validation_id,attempt,lease_token,kind,status) values(:id,1,:token,'MODEL','RUNNING') returning id")
                .param("id", validation).param("token", lease).query(Long.class).single();
        var execution = new ValidationOutcomeWriter.Execution(project, target, validation, run, 1, lease,
                baseline, revision, Map.of(), "gms", caller);
        Map<String, Object> request = Map.of(
                "project_id", Long.toString(project), "validation_id", Long.toString(validation), "attempt", 1,
                "input_snapshot_hash", "sha256:fixture",
                "plan_revision", Map.of("id", Long.toString(revision), "task_id", Long.toString(target)),
                "approved_items", List.of(Map.of("id", Long.toString(version), "design_item_id", Long.toString(item))),
                "related_plans", List.of(), "unresolved", List.of(), "evidence", List.of(),
                "checks", List.of(Map.of("key", "plan", "design_item_version_ids", List.of(), "related_plan_revision_ids", List.of())));
        Map<String, Object> response = Map.of("origin", "MODEL", "model", "test-model", "usage", List.of(Map.of(
                "request_key", "llm-validate-" + validation + "-attempt-1-call-1", "feature", "TASK_VALIDATION",
                "model", "test-model", "failed", false, "tokens_known", false, "latency_ms", 12)),
                "result", Map.of("analysis_status", "COMPLETE", "checked_keys", List.of("plan"),
                        "unassessed", List.of(), "findings", List.of()));

        // A task appears after the model input snapshot was collected.
        create("new related task", api.readsRef("API-01"), "[]");
        assertThat(writer.complete(execution, request, response, true))
                .isEqualTo(ValidationOutcomeWriter.Applied.DISCARDED);
        assertThat(api.jdbc.sql("select status from validation where id=:id").param("id", validation)
                .query(String.class).single()).isEqualTo("STALE");
        assertThat(api.jdbc.sql("select status from validation_run where id=:id").param("id", run)
                .query(String.class).single()).isEqualTo("DISCARDED");
    }

    @Test
    void rejectsOtherProjectsMembersAndStaleRevision() {
        long target = create("target", api.readsRef("API-01"), "[]");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:id")
                .param("id", target).query(Long.class).single();
        assertThatThrownBy(() -> finder.find(CallerContext.human(api.accountId("outsider@test")),
                project, target, revision, List.of(), 20)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> finder.find(CallerContext.human(member),
                project, target, revision + 999, List.of(), 20)).isInstanceOf(ApiException.class);
    }
}
