package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.service.RelatedTaskCandidateFinder;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RelatedTaskCandidateFinderIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired RelatedTaskCandidateFinder finder;
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
