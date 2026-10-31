package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.relation.dto.RelationAction;
import com.beez.backend.relation.dto.RelationChangeRequest;
import com.beez.backend.relation.dto.RelationCommand;
import com.beez.backend.relation.service.RelationStorageService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses PostgreSQL and the real #167 relation service; proposal fixtures remain provisional. */
class ImpactContextQueryIntegrationTests extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ImpactContextQuery query;
    @Autowired ProposalCommandService proposalCommands;
    @Autowired RelationStorageService dependencies;
    @Autowired PlatformTransactionManager transactions;
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

    /** Convert legacy raw SQL fixtures into the same immutable submitted shape as #195. */
    private void ensureFixtureRevision(long proposal) {
        if (api.jdbc.sql("select count(*) from change_proposal_revision where change_proposal_id=:id")
                .param("id", proposal).query(Long.class).single() != 0) return;
        var rows = api.jdbc.sql("""
                select op,design_item_id,before_version_id,new_kind,schema_version,proposed_content::text as content
                  from change_proposal_item where change_proposal_id=:proposal order by id
                """).param("proposal", proposal).query().listOfRows();
        var items = new ArrayList<ProposalDtos.Item>();
        for (var row : rows) {
            items.add(new ProposalDtos.Item(row.get("op").toString(), str(row.get("design_item_id")),
                    str(row.get("before_version_id")), str(row.get("new_kind")),
                    row.get("schema_version") == null ? null : ((Number) row.get("schema_version")).intValue(),
                    row.get("content") == null ? null : DesignJson.object(row.get("content"))));
        }
        String reason = api.jdbc.sql("select reason from change_proposal where id=:id")
                .param("id", proposal).query(String.class).single();
        var input = new ProposalDtos.Input(Long.toString(baseline), "fixture", reason, null, null,
                List.copyOf(items), List.of(), null, null, null);
        insertRevision(proposal, new ProposalDtos.Snapshot(input, List.of(), false), false);
    }

    private static String str(Object value) { return value == null ? null : value.toString(); }

    private void insertRevision(long proposal, ProposalDtos.Snapshot snapshot, boolean badHash) {
        api.jdbc.sql("""
                insert into change_proposal_revision(change_proposal_id,project_id,revision_no,base_baseline_id,
                        snapshot,snapshot_hash,submitted_by)
                values(:proposal,:project,1,:baseline,cast(:snapshot as jsonb),:hash,:actor)
                """).param("proposal", proposal).param("project", projectId).param("baseline", baseline)
                .param("snapshot", DesignJson.canonical(snapshot))
                .param("hash", badHash ? "sha256:" + "0".repeat(64) : DesignJson.hash(snapshot))
                .param("actor", leader).update();
    }

    /** The exact V10 PostgreSQL jsonb::text legacy backfill shape and hash. */
    private void insertLegacyRevision(long proposal) {
        api.jdbc.sql("""
                insert into change_proposal_revision(change_proposal_id,project_id,revision_no,base_baseline_id,
                        snapshot,snapshot_hash,submitted_by,created_at)
                select c.id,c.project_id,1,c.base_baseline_id,s.value,
                       'sha256:'||encode(sha256(convert_to(s.value::text,'UTF8')),'hex'), c.proposer_account_id,c.created_at
                  from change_proposal c cross join lateral (
                    select jsonb_build_object('legacy',true,'input',jsonb_build_object(
                      'base_baseline_id',c.base_baseline_id::text,'title',c.title,'reason',c.reason,
                      'task_id',c.task_id::text,'validation_id',c.validation_id::text,
                      'items',coalesce((select jsonb_agg(jsonb_build_object('operation',i.op,
                        'design_item_id',i.design_item_id::text,'base_design_item_version_id',i.before_version_id::text,
                        'kind',i.new_kind,'schema_version',i.schema_version,'proposed_content',i.proposed_content) order by i.id)
                        from change_proposal_item i where i.change_proposal_id=c.id),'[]'::jsonb),
                      'documents','[]'::jsonb),'documents','[]'::jsonb) as value
                  ) s where c.id=:proposal and c.project_id=:project
                """).param("proposal", proposal).param("project", projectId).update();
    }

    private ImpactContextQuery.Snapshot load(long proposal) {
        ensureFixtureRevision(proposal);
        return query.load(CallerContext.human(member), projectId, proposal);
    }

    @Test
    void realSubmittedCreateCarriesSchemaAndRevisionIntoAiRequest() {
        var item = new ProposalDtos.Item("CREATE", null, null, "REQUIREMENT", 1,
                Map.of("description", "new login requirement"));
        var input = new ProposalDtos.Input(Long.toString(baseline), "requirement", "new login request",
                null, null, List.of(item), List.of(), null, null, null);
        var submitted = proposalCommands.create(CallerContext.human(member), projectId, input, "real-impact-create");
        var snapshot = query.load(CallerContext.human(member), projectId, Long.parseLong(submitted.id()));
        assertThat(snapshot.proposal().revisionNo()).isEqualTo(submitted.revision());
        assertThat(snapshot.proposal().snapshotHash()).isEqualTo(submitted.snapshotHash());
        var change = snapshot.changes().getFirst();
        assertThat(change.proposalItemIndex()).isZero();
        assertThat(change.operation()).isEqualTo("CREATE");
        assertThat(change.itemId()).isNull();
        assertThat(change.schemaVersion()).isEqualTo(1);
        var body = ImpactRequestAssembler.assemble(snapshot, "request");
        var sent = (Map<?, ?>) ((List<?>) body.get("changes")).getFirst();
        assertThat(sent.get("schema_version")).isEqualTo(1);
        assertThat(sent.containsKey("design_item_id")).isFalse();
    }

    @Test
    void postgresLegacyUpdateSnapshotHashIsVerifiedButMissingSchemaIsNotEvaluated() {
        long proposal = proposal("API-01");
        insertLegacyRevision(proposal);
        var snapshot = query.load(CallerContext.human(member), projectId, proposal);
        assertThat(snapshot.changes().getFirst().schemaVersion()).isNull();
        var ai = mock(AiClient.class);
        var outcome = new ImpactAnalysisCoordinator(query, ai)
                .analyze(CallerContext.human(member), projectId, proposal, "legacy-req");
        assertThat(outcome.status()).isEqualTo(ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(outcome.errorCode()).isEqualTo("change_schema_version_unavailable");
        verifyNoInteractions(ai);
    }

    @Test
    void postgresLegacyRetireSnapshotWithoutSchemaIsStillReadable() {
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                update change_proposal_item set op='RETIRE', proposed_content=null where change_proposal_id=:id
                """).param("id", proposal).update();
        insertLegacyRevision(proposal);
        var snapshot = query.load(CallerContext.human(member), projectId, proposal);
        assertThat(snapshot.changes().getFirst().operation()).isEqualTo("RETIRE");
        assertThat(snapshot.changes().getFirst().schemaVersion()).isNull();
        query.requireUnchanged(CallerContext.human(member), snapshot);
    }

    @Test
    void realRejectedAndResubmittedProposalInvalidatesOldAnalysis() {
        var item = new ProposalDtos.Item("UPDATE", Long.toString(api.designItemId("API-01")),
                Long.toString(api.currentVersionId("API-01")), null, 1,
                Map.of("description", "updated login", "method", "GET", "path", "/v2/users"));
        var input = new ProposalDtos.Input(Long.toString(baseline), "API change", "member field update",
                null, null, List.of(item), List.of(), null, null, null);
        var submitted = proposalCommands.create(CallerContext.human(member), projectId, input, "real-revision-create");
        long proposal = Long.parseLong(submitted.id());
        var before = query.load(CallerContext.human(member), projectId, proposal);

        proposalCommands.reject(CallerContext.human(leader), projectId, proposal,
                new ProposalDtos.RejectRequest("please improve", submitted.revision(), submitted.snapshotHash()), "real-revision-reject");
        var retry = new ProposalDtos.Input(Long.toString(baseline), "API change", "member field update",
                null, null, List.of(item), List.of(), submitted.revision(), submitted.snapshotHash(), null);
        var resubmitted = proposalCommands.resubmit(CallerContext.human(member), projectId, proposal, retry, "real-revision-resubmit");
        assertThat(resubmitted.revision()).isEqualTo(submitted.revision() + 1);
        assertThatThrownBy(() -> query.requireUnchanged(CallerContext.human(member), before))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("analysis_input_changed");
    }

    @Test
    void realSubmittedProposalUsesImmutableRevisionSchemaAndHash() {
        var item = new ProposalDtos.Item("UPDATE", Long.toString(api.designItemId("API-01")),
                Long.toString(api.currentVersionId("API-01")), null, 1,
                Map.of("description", "updated login", "method", "GET", "path", "/v2/users"));
        var input = new ProposalDtos.Input(Long.toString(baseline), "API change", "member field update",
                null, null, List.of(item), List.of(), null, null, null);
        var submitted = proposalCommands.create(CallerContext.human(member), projectId, input, "real-impact-update");
        var snapshot = query.load(CallerContext.human(member), projectId, Long.parseLong(submitted.id()));
        assertThat(snapshot.proposal().revisionNo()).isEqualTo(submitted.revision());
        assertThat(snapshot.proposal().snapshotHash()).isEqualTo(submitted.snapshotHash());
        assertThat(snapshot.changes()).hasSize(1);
        assertThat(snapshot.changes().getFirst().proposalItemIndex()).isZero();
        assertThat(snapshot.changes().getFirst().schemaVersion()).isEqualTo(1);
        assertThat(snapshot.changes().getFirst().proposedContent()).contains("/v2/users");

        // Projection row changes must never replace what was actually submitted.
        api.jdbc.sql("""
                update change_proposal_item set proposed_content='{"path":"/tampered"}'::jsonb
                 where change_proposal_id=:proposal
                """).param("proposal", Long.parseLong(submitted.id())).update();
        assertThat(query.load(CallerContext.human(member), projectId, Long.parseLong(submitted.id()))
                .changes().getFirst().proposedContent()).contains("/v2/users").doesNotContain("/tampered");
    }

    @Test
    void malformedStoredSnapshotHashIsRejected() {
        long proposal = proposal("API-01");
        var item = new ProposalDtos.Item("UPDATE", Long.toString(api.designItemId("API-01")),
                Long.toString(api.currentVersionId("API-01")), "API_CONTRACT", 1,
                Map.of("description", "changed", "method", "GET", "path", "/changed"));
        var input = new ProposalDtos.Input(Long.toString(baseline), "bad", "bad", null, null,
                List.of(item), List.of(), null, null, null);
        insertRevision(proposal, new ProposalDtos.Snapshot(input, List.of(), false), true);
        assertThatThrownBy(() -> query.load(CallerContext.human(member), projectId, proposal))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("analysis_snapshot_invalid");
    }

    @Test
    void documentOnlyProposalIsMarkedUnevaluatedInsteadOfZeroImpact() {
        long proposal = api.jdbc.sql("""
                insert into change_proposal(project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,reason)
                values(:project,'CP-DOC','PROPOSED',:baseline,'HUMAN',:actor,'document change') returning id
                """).param("project", projectId).param("baseline", baseline).param("actor", leader).query(Long.class).single();
        var document = new ProposalDtos.DocumentChange("10", "11", "after", null);
        var input = new ProposalDtos.Input(Long.toString(baseline), "doc", "document change", null, null,
                List.of(), List.of(document), null, null, null);
        var resolved = new ProposalDtos.ResolvedDocument("10", "11", "12", "after", "sha256:dummy");
        insertRevision(proposal, new ProposalDtos.Snapshot(input, List.of(resolved), false), false);
        var snapshot = query.load(CallerContext.human(member), projectId, proposal);
        assertThat(snapshot.documentChanges()).isTrue();
        assertThat(snapshot.changes()).isEmpty();
        assertThat(snapshot.ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
    }

    @Test
    void loadsProjectScopedDirectAndConfirmedIndirectEvidenceBeforeApproval() {
        long directTask = taskReferencing("API-01", "직접 참조");
        long indirectTask = taskReferencing("API-02", "간접 참조");
        long proposal = proposal("API-01");
        new TransactionTemplate(transactions).execute(tx -> dependencies.apply(CallerContext.human(leader), projectId,
                new RelationChangeRequest(baseline, null, java.util.List.of(), null,
                        java.util.List.of(new RelationCommand(RelationAction.CONFIRM,
                                api.designItemId("API-02"), api.designItemId("API-01"), "API-02 depends on API-01")))));

        var snapshot = load(proposal);

        assertThat(snapshot.proposal().reason()).isEqualTo("field rename");
        assertThat(snapshot.changes()).hasSize(1);
        assertThat(snapshot.approvedItems()).hasSize(2);
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == directTask);
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == indirectTask);
        assertThat(snapshot.dependencies()).hasSize(1);
        assertThat(snapshot.dependencies().getFirst().fromItemId()).isEqualTo(api.designItemId("API-02"));
        assertThat(snapshot.dependencies().getFirst().toItemId()).isEqualTo(api.designItemId("API-01"));
        assertThat(snapshot.ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
        assertThat(snapshot.relationSetRevision()).isEqualTo(1);
        assertThat(snapshot.taskReferences()).allMatch(ref -> ref.validationId() == null);
        query.requireUnchanged(CallerContext.human(member), snapshot);
    }

    @Test
    void changedTaskAfterAnalysisInvalidatesSnapshot() {
        long task = taskReferencing("API-01", "직접 참조");
        long proposal = proposal("API-01");
        var snapshot = load(proposal);
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
        new TransactionTemplate(transactions).execute(tx -> dependencies.apply(CallerContext.human(member), projectId,
                new RelationChangeRequest(baseline, null, java.util.List.of(), null,
                        java.util.List.of(new RelationCommand(RelationAction.ADD_CANDIDATE,
                                api.designItemId("API-02"), api.designItemId("API-01"), "candidate only")))));

        var snapshot = load(proposal);

        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
    }

    @Test
    void relationRecheckIsNotReadAsConfirmedAndInvalidatesPreviousSnapshot() {
        long proposal = proposal("API-01");
        long changed = api.designItemId("API-01");
        long dependent = api.designItemId("API-02");
        new TransactionTemplate(transactions).execute(tx -> dependencies.apply(CallerContext.human(leader), projectId,
                new RelationChangeRequest(baseline, null, java.util.List.of(), null,
                        java.util.List.of(new RelationCommand(RelationAction.CONFIRM, dependent, changed, "confirmed")))));
        var before = load(proposal);
        assertThat(before.dependencies()).hasSize(1);
        new TransactionTemplate(transactions).execute(tx -> dependencies.apply(CallerContext.human(leader), projectId,
                new RelationChangeRequest(baseline, null, java.util.List.of(changed), "source changed", java.util.List.of())));
        var after = load(proposal);
        assertThat(after.dependencies()).isEmpty();
        assertThat(after.relationSetRevision()).isGreaterThan(before.relationSetRevision());
        assertThatThrownBy(() -> query.requireUnchanged(CallerContext.human(member), before))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("analysis_input_changed");
    }

    @Test
    void oppositeDirectionIsNotUsedAsAnIncomingDependency() {
        long proposal = proposal("API-01");
        new TransactionTemplate(transactions).execute(tx -> dependencies.apply(CallerContext.human(leader), projectId,
                new RelationChangeRequest(baseline, null, java.util.List.of(), null,
                        java.util.List.of(new RelationCommand(RelationAction.CONFIRM,
                                api.designItemId("API-01"), api.designItemId("API-02"), "reverse direction")))));
        var snapshot = load(proposal);
        assertThat(snapshot.dependencies()).isEmpty();
        assertThat(snapshot.relationSetRevision()).isEqualTo(1);
        assertThat(snapshot.ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
    }

    @Test
    void withdrawnProposalCannotBeAnalyzed() {
        long proposal = proposal("API-01");
        ensureFixtureRevision(proposal);
        api.jdbc.sql("""
                update change_proposal set status='WITHDRAWN', withdrawn_by=:actor, withdrawn_at=now()
                 where id=:id
                """).param("actor", leader).param("id", proposal).update();
        assertThatThrownBy(() -> load(proposal))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode()).isEqualTo("proposal_not_analyzable");
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

        var snapshot = load(proposal);

        assertThat(snapshot.searchCoverage().status()).isEqualTo(ImpactKeywordSearch.Status.SEARCHED);
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == taskId && ref.designItemId() == null);
        assertThat(snapshot.ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
        var request = ImpactRequestAssembler.assemble(snapshot, "request");
        assertThat(((java.util.List<?>) request.get("open_tasks"))).hasSize(1);
        query.requireUnchanged(CallerContext.human(member), snapshot);
    }

    @Test
    void newlyMatchingTaskMakesOriginalSearchSnapshotStale() {
        long proposal = proposal("API-01");
        var before = load(proposal);
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
        var snapshot = load(proposal);
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

        var snapshot = load(proposal);
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

        var snapshot = load(proposal);

        assertThat(snapshot.approvedItems()).anyMatch(item -> item.itemId() == api.designItemId("API-02"));
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == taskId
                && ref.designItemId() == api.designItemId("API-02"));
        assertThat(snapshot.searchCoverage().candidateTaskIds()).contains(taskId);
        // Keyword relevance to API-02 is a candidate, not a confirmed dependency to API-01.
        assertThat(snapshot.ruleCandidateStatus()).isEqualTo(ImpactContextQuery.RuleCandidateStatus.NOT_CONNECTED);
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

        var snapshot = load(proposal);

        assertThat(snapshot.approvedItems()).anyMatch(item -> item.itemId() == api.designItemId("API-02"));
        assertThat(snapshot.taskReferences()).anyMatch(ref -> ref.taskId() == taskId);
        assertThat(snapshot.searchCoverage().candidateTaskIds()).contains(taskId);
    }

    @Test
    void newTaskReferencingSearchedDesignInvalidatesEarlierSnapshot() {
        long proposal = proposal("API-01");
        api.jdbc.sql("""
                update change_proposal_item set proposed_content = '{"path":"/orders"}'::jsonb
                where change_proposal_id = :proposal
                """).param("proposal", proposal).update();
        var before = load(proposal);
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

        var snapshot = load(proposal);

        assertThat(snapshot.searchCoverage().tasksTruncated()).isTrue();
        assertThat(snapshot.searchCoverage().candidateTaskIds()).hasSize(ImpactKeywordSearch.MAX_RESULTS);
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

        var snapshot = load(proposal);

        assertThat(snapshot.approvedItems()).anyMatch(item -> item.itemId() == api.designItemId("API-02"));
        assertThat(snapshot.searchCoverage().candidateTaskIds()).doesNotContain(task);
        assertThat(snapshot.taskReferences()).noneMatch(ref -> ref.taskId() == task);
    }

}
