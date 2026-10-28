package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.ontology.DesignDependencyService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Requires the real PostgreSQL integration profile; proposal setup uses SQL until #195 exists. */
@TestPropertySource(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/ontology-draft")
class ImpactContextQueryIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ImpactContextQuery query;
    @Autowired DesignDependencyService dependencies;
    private TaskApiSupport api;
    private long projectId;
    private long leader;
    private long member;
    private long baseline;

    @BeforeEach
    void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id = :p")
                .param("p", projectId).query(Long.class).single();
    }

    private long taskReferencing(String key, String title) {
        var response = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson(title, api.readsRef(key), "[]"), member);
        assertThat(response.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(response.body(), "id");
    }

    private long proposal(String itemKey) {
        long id = api.jdbc.sql("""
                insert into change_proposal
                    (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                values (:p, 'CP-IMPACT', 'PROPOSED', :b, 'HUMAN', :a, 'field rename')
                returning id
                """).param("p", projectId).param("b", baseline).param("a", leader).query(Long.class).single();
        api.jdbc.sql("""
                insert into change_proposal_item
                    (change_proposal_id, op, design_item_id, before_version_id, proposed_content)
                values (:proposal, 'UPDATE', :item, :version, '{"memberId":"long"}'::jsonb)
                """).param("proposal", id).param("item", api.designItemId(itemKey))
                .param("version", api.currentVersionId(itemKey)).update();
        return id;
    }

    @Test
    void loadsProjectScopedDirectAndConfirmedIndirectEvidenceBeforeApproval() {
        long directTask = taskReferencing("API-01", "직접 참조");
        long indirectTask = taskReferencing("API-02", "간접 참조");
        long proposal = proposal("API-01");
        long event = dependencies.confirm(CallerContext.human(leader), projectId, baseline,
                api.designItemId("API-02"), api.designItemId("API-01"), "API-02 depends on API-01");

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.proposal().reason()).isEqualTo("field rename");
        assertThat(snapshot.changes()).hasSize(1);
        assertThat(snapshot.approvedItems()).hasSize(2);
        assertThat(snapshot.candidates().paths()).hasSize(2);
        assertThat(snapshot.candidates().paths()).anyMatch(path ->
                path.taskId() == directTask && path.kind() == ImpactCandidateFinder.Kind.DIRECT_REFERENCE);
        assertThat(snapshot.candidates().paths()).anyMatch(path ->
                path.taskId() == indirectTask && path.kind() == ImpactCandidateFinder.Kind.INDIRECT_DEPENDENCY
                        && path.confirmationEventId() == event);
        assertThat(snapshot.candidates().indirectCoverage()).isEqualTo(ImpactCandidateFinder.Coverage.UNKNOWN);
        assertThat(snapshot.taskReferences()).allMatch(ref -> ref.validationId() == null);
        query.requireUnchanged(CallerContext.human(member), snapshot);
    }

    @Test
    void changedTaskAfterAnalysisInvalidatesSnapshot() {
        long task = taskReferencing("API-01", "직접 참조");
        long proposal = proposal("API-01");
        var snapshot = query.load(CallerContext.human(member), projectId, proposal);
        api.jdbc.sql("update task set lock_version = lock_version + 1 where id = :id")
                .param("id", task).update();

        assertThatThrownBy(() -> query.requireUnchanged(CallerContext.human(member), snapshot))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("analysis_input_changed");
    }

    @Test
    void neverUsesLegacyExplicitRowsAsConfirmedOrTreatsMissingPathsAsNoImpact() {
        taskReferencing("API-02", "미확인 관계만 있음");
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                insert into design_item_dependency (project_id, from_item_id, to_item_id, origin)
                values (:p, :f, :t, 'EXPLICIT')
                """).param("p", projectId).param("f", api.designItemId("API-02"))
                .param("t", api.designItemId("API-01")).update();

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.candidates().paths()).isEmpty();
        assertThat(snapshot.candidates().indirectCoverage()).isEqualTo(ImpactCandidateFinder.Coverage.UNKNOWN);
    }

    @Test
    void deniesProposalFromDifferentProject() {
        long proposal = proposal("API-01");
        assertThatThrownBy(() -> query.load(CallerContext.human(member), projectId + 9999, proposal))
                .isInstanceOf(ApiException.class);
    }
    @Test
    void newDesignFindsRelatedUnlinkedOpenTaskWithoutCreatingRuleRelationship() {
        var response = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson("memberId client", "[]", "[]"), member);
        assertThat(response.statusCode()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(response.body(), "id");
        long proposal = api.jdbc.sql("""
                insert into change_proposal
                    (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                values (:p, 'CP-NEW', 'PROPOSED', :b, 'HUMAN', :a, 'new member endpoint')
                returning id
                """).param("p", projectId).param("b", baseline).param("a", leader).query(Long.class).single();
        api.jdbc.sql("""
                insert into change_proposal_item
                    (change_proposal_id, op, new_key, new_kind, proposed_content)
                values (:proposal, 'CREATE', 'API-NEW', 'API_CONTRACT', '{"response":{"memberId":"long"}}'::jsonb)
                """).param("proposal", proposal).update();

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.searchCoverage().status()).isEqualTo(ImpactKeywordSearch.Status.SEARCHED);
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == taskId && ref.designItemId() == null);
        assertThat(snapshot.candidates().paths()).isEmpty();
        assertThat(snapshot.candidates().indirectCoverage()).isEqualTo(ImpactCandidateFinder.Coverage.UNKNOWN);
        var request = ImpactRequestAssembler.assemble(snapshot, "request");
        assertThat(((java.util.List<?>) request.get("open_tasks"))).hasSize(1);
        query.requireUnchanged(CallerContext.human(member), snapshot);
    }

    @Test
    void newlyMatchingTaskMakesOriginalSearchSnapshotStale() {
        long proposal = proposal("API-01");
        var before = query.load(CallerContext.human(member), projectId, proposal);
        var response = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson("memberId client", "[]", "[]"), member);
        assertThat(response.statusCode()).isEqualTo(201);

        assertThatThrownBy(() -> query.requireUnchanged(CallerContext.human(member), before))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("analysis_input_changed");
    }

    @Test
    void keywordSearchHasAnExplicitLimitAndReportsTruncation() {
        long proposal = proposal("API-01");
        for (int i = 0; i <= ImpactKeywordSearch.MAX_RESULTS; i++) {
            var response = api.post("/api/projects/" + projectId + "/tasks",
                    api.planJson("memberId task " + i, "[]", "[]"), member);
            assertThat(response.statusCode()).isEqualTo(201);
        }
        var snapshot = query.load(CallerContext.human(member), projectId, proposal);
        assertThat(snapshot.searchCoverage().tasksTruncated()).isTrue();
        assertThat(snapshot.taskReferences().stream().map(ImpactContextQuery.TaskReference::taskId).distinct().count())
                .isEqualTo(ImpactKeywordSearch.MAX_RESULTS);
    }

    @Test
    void keywordSearchNeverIncludesAnotherProjectsTasks() {
        long proposal = proposal("API-01");
        long other = api.jdbc.sql("insert into project (name, owner_account_id) values ('OTHER', :a) returning id")
                .param("a", leader).query(Long.class).single();
        long task = api.jdbc.sql("""
                insert into task (project_id, key, title, status)
                values (:p, 'OTHER-1', 'memberId private', 'DRAFT') returning id
                """).param("p", other).query(Long.class).single();
        long revision = api.jdbc.sql("""
                insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id)
                values (:t, 1, 'memberId private', 'memberId', 'other-hash', :a) returning id
                """).param("t", task).param("a", leader).query(Long.class).single();
        api.jdbc.sql("update task set current_plan_revision_id = :r, status = 'READY' where id = :t")
                .param("r", revision).param("t", task).update();

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);
        assertThat(snapshot.taskReferences()).noneMatch(ref -> ref.taskId() == task);
    }

    @Test
    void searchedApprovedDesignExpandsToReferencingTaskAsSearchCandidateOnly() {
        long taskId = taskReferencing("API-02", "다른 화면 작업");
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                update change_proposal_item
                   set proposed_content = '{"path":"/orders"}'::jsonb
                 where change_proposal_id = :proposal
                """).param("proposal", proposal).update();

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.approvedItems()).anyMatch(item -> item.itemId() == api.designItemId("API-02"));
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == taskId
                && ref.designItemId() == api.designItemId("API-02"));
        assertThat(snapshot.searchCoverage().candidateTaskIds()).contains(taskId);
        // Keyword relevance to API-02 is a candidate, not a confirmed dependency to API-01.
        assertThat(snapshot.candidates().paths()).isEmpty();
        assertThat(snapshot.candidates().indirectCoverage()).isEqualTo(ImpactCandidateFinder.Coverage.UNKNOWN);
    }

    @Test
    void newDesignFindsTaskReferencingMatchingApprovedDesignWithoutTaskKeyword() {
        long taskId = taskReferencing("API-02", "다른 화면 작업");
        long proposal = api.jdbc.sql("""
                insert into change_proposal
                    (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                values (:p, 'CP-NEW-DESIGN', 'PROPOSED', :b, 'HUMAN', :a, '새 설계')
                returning id
                """).param("p", projectId).param("b", baseline).param("a", leader).query(Long.class).single();
        api.jdbc.sql("""
                insert into change_proposal_item
                    (change_proposal_id, op, new_key, new_kind, proposed_content)
                values (:proposal, 'CREATE', 'API-NEW', 'API_CONTRACT', '{"path":"/orders"}'::jsonb)
                """).param("proposal", proposal).update();

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.approvedItems()).anyMatch(item -> item.itemId() == api.designItemId("API-02"));
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == taskId);
        assertThat(snapshot.searchCoverage().candidateTaskIds()).contains(taskId);
        assertThat(snapshot.candidates().paths()).isEmpty();
    }

    @Test
    void newTaskReferencingSearchedDesignInvalidatesEarlierSnapshot() {
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                update change_proposal_item set proposed_content = '{"path":"/orders"}'::jsonb
                where change_proposal_id = :proposal
                """).param("proposal", proposal).update();
        var before = query.load(CallerContext.human(member), projectId, proposal);
        taskReferencing("API-02", "다른 화면 작업");

        assertThatThrownBy(() -> query.requireUnchanged(CallerContext.human(member), before))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("analysis_input_changed");
    }

    @Test
    void approvedDesignExpansionRespectsTaskLimitAndReportsTruncation() {
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                update change_proposal_item set proposed_content = '{"path":"/orders"}'::jsonb
                where change_proposal_id = :proposal
                """).param("proposal", proposal).update();
        for (int i = 0; i <= ImpactKeywordSearch.MAX_RESULTS; i++) {
            taskReferencing("API-02", "다른 화면 작업 " + i);
        }

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.searchCoverage().tasksTruncated()).isTrue();
        assertThat(snapshot.searchCoverage().candidateTaskIds()).hasSize(ImpactKeywordSearch.MAX_RESULTS);
        assertThat(snapshot.candidates().paths()).isEmpty();
    }

    @Test
    void approvedDesignExpansionNeverReturnsTaskFromAnotherProject() {
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                update change_proposal_item set proposed_content = '{"path":"/orders"}'::jsonb
                where change_proposal_id = :proposal
                """).param("proposal", proposal).update();
        long other = api.jdbc.sql("insert into project (name, owner_account_id) values ('OTHER', :a) returning id")
                .param("a", leader).query(Long.class).single();
        long task = api.jdbc.sql("""
                insert into task (project_id, key, title, status)
                values (:p, 'OTHER-1', '다른 프로젝트', 'DRAFT') returning id
                """).param("p", other).query(Long.class).single();
        long revision = api.jdbc.sql("""
                insert into task_plan_revision (task_id, revision_no, title, goal, input_hash, created_by_account_id)
                values (:t, 1, '다른 화면', '화면 개발', 'other-hash', :a) returning id
                """).param("t", task).param("a", leader).query(Long.class).single();
        // V2 does not enforce project scope on this reference. A malformed cross-project
        // row must not make the other project's task visible in the query.
        api.jdbc.sql("""
                insert into task_plan_design_ref
                    (plan_revision_id, design_item_id, design_item_version_id, relation)
                values (:r, :i, :v, 'READS')
                """).param("r", revision).param("i", api.designItemId("API-02"))
                .param("v", api.currentVersionId("API-02")).update();
        api.jdbc.sql("update task set current_plan_revision_id = :r, status = 'READY' where id = :t")
                .param("r", revision).param("t", task).update();

        var snapshot = query.load(CallerContext.human(member), projectId, proposal);

        assertThat(snapshot.approvedItems()).anyMatch(item -> item.itemId() == api.designItemId("API-02"));
        assertThat(snapshot.searchCoverage().candidateTaskIds()).doesNotContain(task);
        assertThat(snapshot.taskReferences()).noneMatch(ref -> ref.taskId() == task);
    }

}
