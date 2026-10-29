package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.validation.service.ValidationRuleChecker;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Executes rule SQL against the V2 schema. Requires Docker-backed PostgreSQL. */
class ValidationRuleCheckerIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ValidationRuleChecker checker;
    TaskApiSupport api;
    long project, owner, baseline;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        owner = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:id").param("id", project).query(Long.class).single();
    }

    private long create(String title, boolean changing, String dependencies) {
        String ref = changing ? api.changesRef("API-01") : api.readsRef("API-01");
        String plan = api.planJson(title, ref, dependencies);
        if (changing) plan = plan.replace("\"no_contract_change\": true", "\"no_contract_change\": false");
        var response = api.post("/api/projects/" + project + "/tasks", plan, owner);
        assertThat(response.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(response.body(), "id");
    }

    private long revision(long task) {
        return api.jdbc.sql("select current_plan_revision_id from task where id=:id").param("id", task).query(Long.class).single();
    }

    @Test
    void detectsActiveChangeInAnotherTaskWithoutConfusingSameProjectIds() {
        long target = create("대상", true, "[]"), other = create("다른 작업", true, "[]");
        long run = api.jdbc.sql("""
                insert into validation(task_id,plan_revision_id,baseline_id,status,trigger,input_snapshot_hash,rule_version)
                values(:task,:revision,:baseline,'PASS','AGENT','test-snapshot','test') returning id
                """).param("task", other).param("revision", revision(other)).param("baseline", baseline).query(Long.class).single();
        api.jdbc.sql("update task set status='IN_PROGRESS',started_validation_id=:run where id=:task")
                .param("run", run).param("task", other).update();
        var result = checker.check(project, target, revision(target), baseline);
        assertThat(result.findings()).extracting(ValidationRuleChecker.Finding::kind).contains("CONFLICT");
        assertThat(result.findings().stream().filter(f -> "CONFLICT".equals(f.kind())).findFirst().orElseThrow().relatedTaskId()).isEqualTo(other);
        assertThat(result.allRulesVerified()).isFalse();
    }

    @Test
    void rejectsNonCurrentSnapshot() {
        long target = create("대상", false, "[]");
        assertThatThrownBy(() -> checker.check(project, target, revision(target) + 1000, baseline))
                .isInstanceOf(IllegalStateException.class);
    }
}
