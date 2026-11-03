package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

class OwnerRecommendationIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired OwnerRecommendationService owners;
    @Autowired ImpactRunService runs;
    @Autowired ImpactContextQuery contexts;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AiClient ai;
    @MockitoBean ImpactRunExecutor executor;
    @MockitoBean DocumentStorage storage;

    TaskApiSupport api;
    long project, baseline, member, leader, proposal;
    String hash;
    CallerContext caller;
    final List<Runnable> queued = new CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws java.io.IOException {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        queued.clear();
        project = api.projectId();
        member = api.accountId("member@test");
        leader = api.accountId("leader@test");
        caller = CallerContext.human(member);
        baseline = api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p", project).query(Long.class).single();
        var created = proposals.create(caller, project, new Input(Long.toString(baseline), "retire", "retire API", null, null,
                List.of(new Item("RETIRE", api.designItemId("API-01").toString(), api.currentVersionId("API-01").toString(),
                        null, null, null)), List.of(), null, null, null), "owner-test-create");
        proposal = Long.parseLong(created.id());
        hash = created.snapshotHash();
        when(executor.submit(any())).thenAnswer(call -> { queued.add(call.getArgument(0)); return true; });
        when(storage.store(any(), any())).thenAnswer(call -> "owner-fixture/" + java.util.UUID.randomUUID() + ".md");
    }

    long task(String title, String designKey, long account) {
        var result = api.post("/api/projects/" + project + "/tasks", api.planJson(title, api.readsRef(designKey), "[]"), account);
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(201);
        return TaskApiSupport.longField(result.body(), "id");
    }

    void work() { queued.removeFirst().run(); }

    OwnerRecommendationDtos.DirectResult notification(CallerContext actor, int revision, String snapshotHash) {
        return new TransactionTemplate(transactions).execute(ignored ->
                owners.forNotification(actor, project, proposal, revision, snapshotHash));
    }

    @Test void noRelatedTasksNeverExpandsToTheWholeTeamAndGetIncludesTheHandoff() {
        var direct = notification(caller, 1, hash);
        assertThat(direct.directReferenceStatus()).isEqualTo("COMPLETE");
        assertThat(direct.recommendations()).isEmpty();
        assertThat(direct.notificationCandidateMemberIds()).isEmpty();
        var review = owners.forReview(caller, project, proposal);
        assertThat(review.owners().aiAnalysisStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(review.owners().recommendations()).isEmpty();
        verifyNoInteractions(ai);

        var response = api.get("/api/projects/" + project + "/change-proposals/" + proposal + "/impact", member);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"owner_recommendations\"", "\"direct_reference_status\":\"COMPLETE\"",
                "\"notification_candidate_member_ids\":[]", "\"analysis_status\":\"NOT_EVALUATED\"",
                "\"proposal_revision\":1", "\"ai_current\":true", "\"ai_reanalysis_in_progress\":false",
                "\"withheld_ai_candidates\":0");
        assertThat(api.get("/api/projects/" + project + "/change-proposals/" + proposal + "/impact",
                api.accountId("outsider@test")).statusCode()).isEqualTo(404);
    }

    @Test void directEvidenceDeduplicatesOwnersAndExcludesClosedOrInactiveMembers() {
        long first = task("one", "API-01", member);
        long second = task("two", "API-01", member);
        task("unrelated", "API-02", member);
        long closed = task("closed", "API-01", leader);
        api.jdbc.sql("UPDATE task SET status='CANCELLED' WHERE id=:id").param("id", closed).update();
        api.jdbc.sql("UPDATE project_member SET area='백엔드' WHERE id=:id").param("id", api.memberId("member@test")).update();

        var result = notification(caller, 1, hash);
        assertThat(result.notificationCandidateMemberIds()).containsExactly(api.memberId("member@test").toString());
        assertThat(result.recommendations()).singleElement().satisfies(owner -> {
            assertThat(owner.area()).isEqualTo("백엔드");
            assertThat(owner.evidence()).extracting("taskId").containsExactly(Long.toString(first), Long.toString(second));
            assertThat(owner.evidence()).allSatisfy(e -> {
                assertThat(e.type()).isEqualTo("DIRECT_TASK_REFERENCE");
                assertThat(e.planRevisionId()).isNotBlank();
                assertThat(e.designItemId()).isEqualTo(api.designItemId("API-01").toString());
                assertThat(e.designItemVersionId()).isEqualTo(api.currentVersionId("API-01").toString());
                assertThat(e.referencedDesignItemVersionId()).isEqualTo(api.currentVersionId("API-01").toString());
            });
        });
        var response = api.get("/api/projects/" + project + "/change-proposals/" + proposal + "/impact", member);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"member_id\":\"" + api.memberId("member@test") + "\"",
                "\"type\":\"DIRECT_TASK_REFERENCE\"", "\"task_key\":",
                "\"referenced_design_item_version_id\":\"" + api.currentVersionId("API-01") + "\"");

        api.jdbc.sql("UPDATE project_member SET active=false WHERE id=:id").param("id", api.memberId("member@test")).update();
        var afterRemoval = notification(CallerContext.human(leader), 1, hash);
        assertThat(afterRemoval.recommendations()).isEmpty();
        assertThat(afterRemoval.notificationCandidateMemberIds()).isEmpty();
    }

    @Test void evidenceKeepsTheBaselineVersionSeparateFromTheTasksOlderReference() {
        task("older plan", "API-01", member);
        long oldVersion = api.currentVersionId("API-01");
        long otherVersion = api.currentVersionId("API-02");
        long documentVersion = createDocument("API_SPEC");
        long document = api.jdbc.sql("SELECT document_id FROM document_version WHERE id=:id")
                .param("id", documentVersion).query(Long.class).single();
        long newVersion = api.newVersion("API-01", 2);
        api.jdbc.sql("UPDATE design_item_version SET source_document_version_id=:doc WHERE id=:id")
                .param("doc", documentVersion).param("id", newVersion).update();
        long next = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:project,2,:account) RETURNING id")
                .param("project", project).param("account", leader).query(Long.class).single();
        api.jdbc.sql("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(:base,:project,:item,:version)")
                .param("base", next).param("project", project).param("item", api.designItemId("API-01")).param("version", newVersion).update();
        api.jdbc.sql("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(:base,:project,:item,:version)")
                .param("base", next).param("project", project).param("item", api.designItemId("API-02")).param("version", otherVersion).update();
        api.jdbc.sql("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(:base,:project,:doc,:version)")
                .param("base", next).param("project", project).param("doc", document).param("version", documentVersion).update();
        api.jdbc.sql("UPDATE project SET current_baseline_id=:base WHERE id=:project").param("base", next).param("project", project).update();
        var nextProposal = proposals.create(caller, project, new Input(Long.toString(next), "v2", "version change", null, null,
                List.of(new Item("RETIRE", api.designItemId("API-01").toString(), Long.toString(newVersion), null, null, null)),
                List.of(), null, null, null), "new-baseline-proposal");
        var result = owners.forReview(caller, project, Long.parseLong(nextProposal.id())).owners();
        assertThat(result.recommendations()).singleElement().satisfies(r -> assertThat(r.evidence()).singleElement().satisfies(e -> {
            assertThat(e.designItemVersionId()).isEqualTo(Long.toString(newVersion));
            assertThat(e.referencedDesignItemVersionId()).isEqualTo(Long.toString(oldVersion));
            assertThat(e.documentVersionId()).isEqualTo(Long.toString(documentVersion));
        }));
    }

    @Test void wrongRevisionAndOtherProjectCallerCannotUseTheNotificationHandoff() {
        assertThatThrownBy(() -> owners.forNotification(caller, project, proposal, 1, hash))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> notification(caller, 2, hash))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("version_conflict"));
        assertThatThrownBy(() -> notification(caller, 1, "incorrect"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("version_conflict"));
        assertThatThrownBy(() -> owners.forReview(CallerContext.human(api.accountId("outsider@test")), project, proposal))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test void anOutdatedBaselineCannotProduceNotificationCandidates() {
        task("direct", "API-01", member);
        long next = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:project,2,:account) RETURNING id")
                .param("project", project).param("account", leader).query(Long.class).single();
        api.jdbc.sql("UPDATE project SET current_baseline_id=:baseline WHERE id=:project")
                .param("baseline", next).param("project", project).update();
        var result = notification(caller, 1, hash);
        assertThat(result.directReferenceStatus()).isEqualTo("STALE");
        assertThat(result.directReferenceReasons()).containsExactly("baseline_changed");
        assertThat(result.notificationCandidateMemberIds()).isEmpty();
    }

    @Test void aPublishedChangeStillFindsDirectOwnersFromItsSubmittedBaseline() {
        task("publication consumer", "API-01", member);
        long submittedVersion = api.currentVersionId("API-01");
        var published = publications.approve(CallerContext.human(leader), project, proposal,
                new ApproveRequest(Long.toString(baseline), 1, hash, "분석 전 직접 연결을 검토했습니다."), "publish-owner-test");
        assertThat(published.proposal().status()).isEqualTo("PUBLISHED");
        var result = notification(caller, 1, hash);
        assertThat(result.directReferenceStatus()).isEqualTo("COMPLETE");
        assertThat(result.notificationCandidateMemberIds()).containsExactly(api.memberId("member@test").toString());
        assertThat(result.recommendations()).singleElement().satisfies(owner ->
                assertThat(owner.evidence()).singleElement().satisfies(e ->
                        assertThat(e.designItemVersionId()).isEqualTo(Long.toString(submittedVersion))));
    }

    @Test void modelFailureDoesNotEraseIndependentlyVerifiedDirectOwners() {
        task("direct", "API-01", member);
        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("timeout"));
        runs.retry(caller, project, proposal, null);
        work();
        var result = owners.forReview(caller, project, proposal).owners();
        assertThat(result.aiAnalysisStatus()).isEqualTo("FAILED");
        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.notificationCandidateMemberIds()).containsExactly(api.memberId("member@test").toString());
    }

    @Test void aiCandidatesNeedARealTaskAssigneeAndSourceAndNeverBecomeDirectNotificationTargets() {
        long documentVersion = attachSourceDocument("API-01");
        long document = api.jdbc.sql("SELECT document_id FROM document_version WHERE id=:id").param("id", documentVersion).query(Long.class).single();
        task("direct", "API-01", member);
        long inferred = task("retire consumer", "API-02", leader);
        String leaderMember = api.memberId("leader@test").toString();
        Map<String, Object> source = Map.of("document_id", Long.toString(document), "document_version_id", Long.toString(documentVersion),
                "version_no", 1, "location", "API 계약");
        Map<String, Object> candidate = Map.of("task_id", Long.toString(inferred), "member_id", leaderMember,
                "design_item_id", api.designItemId("API-01").toString(), "reason", "변경 응답을 사용하는 작업입니다.",
                "sources", List.of(source));
        when(ai.changeImpact(any())).thenReturn(envelope(List.of(candidate)));
        runs.retry(caller, project, proposal, null);
        work();

        var result = owners.forReview(caller, project, proposal).owners();
        assertThat(result.aiAnalysisStatus()).isEqualTo("COMPLETED");
        assertThat(result.aiCurrent()).isTrue();
        assertThat(result.recommendations()).hasSize(2);
        assertThat(result.recommendations()).filteredOn(r -> r.memberId().equals(leaderMember)).singleElement()
                .satisfies(r -> assertThat(r.evidence()).singleElement().satisfies(e -> {
                    assertThat(e.type()).isEqualTo("AI_CANDIDATE");
                    assertThat(e.taskId()).isEqualTo(Long.toString(inferred));
                    assertThat(e.sources()).isEqualTo(List.of(source));
                }));
        assertThat(result.notificationCandidateMemberIds()).containsExactly(api.memberId("member@test").toString());

        // Treat a stored candidate as untrusted too: a mismatched member cannot be promoted.
        var forged = Map.of("task_id", Long.toString(inferred), "member_id", api.memberId("member@test").toString(),
                "design_item_id", api.designItemId("API-01").toString(), "reason", "잘못된 담당자", "sources", List.of(source));
        var memberOnly = Map.of("member_id", leaderMember, "reason", "작업 없는 담당자", "sources", List.of(source));
        api.jdbc.sql("UPDATE change_impact_run SET ai_result=cast(:result AS jsonb) WHERE change_proposal_id=:proposal")
                .param("result", DesignJson.canonical(Map.of("candidates", List.of(forged, memberOnly), "evidence_status", "SUFFICIENT")))
                .param("proposal", proposal).update();
        var checked = owners.forReview(caller, project, proposal).owners();
        assertThat(checked.withheldAiCandidates()).isEqualTo(2);
        assertThat(checked.recommendations()).extracting("memberId").containsExactly(api.memberId("member@test").toString());
    }

    @Test void reanalysisAndChangedInputsNeverPresentAnOldAiCandidateAsCurrent() {
        attachSourceDocument("API-01");
        long related = task("retire consumer", "API-01", member);
        var request = ImpactRequestAssembler.assemble(contexts.load(caller, project, proposal), "fixture");
        @SuppressWarnings("unchecked") var items = (List<Map<String, Object>>) request.get("approved_items");
        var source = items.stream().filter(i -> i.get("source") != null).findFirst().orElseThrow().get("source");
        var candidate = Map.of("task_id", Long.toString(related), "member_id", api.memberId("member@test").toString(),
                "design_item_id", api.designItemId("API-01").toString(), "reason", "연결된 작업", "sources", List.of(source));
        when(ai.changeImpact(any())).thenReturn(envelope(List.of(candidate)));
        runs.retry(caller, project, proposal, null);
        work();
        assertThat(owners.forReview(caller, project, proposal).owners().recommendations().getFirst().evidence())
                .extracting("type").contains("AI_CANDIDATE");

        runs.retry(caller, project, proposal, null);
        var during = owners.forReview(caller, project, proposal).owners();
        assertThat(during.aiReanalysisInProgress()).isTrue();
        assertThat(during.recommendations().getFirst().evidence()).extracting("type").containsExactly("DIRECT_TASK_REFERENCE");
        work();
        api.jdbc.sql("UPDATE task SET assignee_member_id=:leader WHERE id=:task")
                .param("leader", api.memberId("leader@test")).param("task", related).update();
        var stale = owners.forReview(caller, project, proposal).owners();
        assertThat(stale.aiCurrent()).isFalse();
        assertThat(stale.recommendations()).extracting("memberId").containsExactly(api.memberId("leader@test").toString());
        assertThat(stale.recommendations().getFirst().evidence()).extracting("type").containsExactly("DIRECT_TASK_REFERENCE");
    }

    @Test void documentOnlyChangesUseRegisteredReferencesAndUnmappedDocumentsRemainIncomplete() {
        long mappedVersion = attachSourceDocument("API-01");
        long mappedDocument = api.jdbc.sql("SELECT document_id FROM document_version WHERE id=:v").param("v", mappedVersion).query(Long.class).single();
        task("document consumer", "API-01", member);
        var mapped = documentProposal(mappedDocument, mappedVersion, "mapped");
        var result = owners.forReview(caller, project, Long.parseLong(mapped.id())).owners();
        assertThat(result.aiAnalysisStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(result.directReferenceStatus()).isEqualTo("COMPLETE");
        assertThat(result.recommendations()).singleElement().satisfies(r -> assertThat(r.evidence()).singleElement()
                .satisfies(e -> assertThat(e.documentVersionId()).isEqualTo(Long.toString(mappedVersion))));

        long unmappedVersion = createDocument("REQ_SPEC");
        long unmappedDocument = api.jdbc.sql("SELECT document_id FROM document_version WHERE id=:v").param("v", unmappedVersion).query(Long.class).single();
        var unmapped = documentProposal(unmappedDocument, unmappedVersion, "unmapped");
        var incomplete = owners.forReview(caller, project, Long.parseLong(unmapped.id())).owners();
        assertThat(incomplete.directReferenceStatus()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.directReferenceReasons()).contains("document_mapping_unavailable");
        assertThat(incomplete.notificationCandidateMemberIds()).isEmpty();
        verifyNoInteractions(ai);
    }

    @Test void truncatedDirectSearchCannotBeUsedAsACompleteNotificationRecipientList() {
        api.jdbc.sql("""
                INSERT INTO task(project_id,key,title,status,assignee_member_id)
                SELECT :project,'BULK-'||n,'bulk','DRAFT',:member FROM generate_series(1,1001) n
                """).param("project", project).param("member", api.memberId("member@test")).update();
        api.jdbc.sql("""
                INSERT INTO task_plan_revision(task_id,revision_no,title,goal,input_hash,created_by_account_id)
                SELECT id,1,title,'bulk','bulk-'||id,:account FROM task WHERE project_id=:project AND key LIKE 'BULK-%'
                """).param("project", project).param("account", member).update();
        api.jdbc.sql("""
                UPDATE task t SET current_plan_revision_id=r.id FROM task_plan_revision r
                 WHERE r.task_id=t.id AND t.project_id=:project AND t.key LIKE 'BULK-%'
                """).param("project", project).update();
        api.jdbc.sql("""
                INSERT INTO task_plan_design_ref(plan_revision_id,design_item_id,design_item_version_id,relation)
                SELECT current_plan_revision_id,:item,:version,'READS' FROM task WHERE project_id=:project AND key LIKE 'BULK-%'
                """).param("project", project).param("item", api.designItemId("API-01"))
                .param("version", api.currentVersionId("API-01")).update();
        var result = notification(caller, 1, hash);
        assertThat(result.directReferenceStatus()).isEqualTo("INCOMPLETE");
        assertThat(result.directReferenceReasons()).contains("direct_results_truncated");
        assertThat(result.notificationCandidateMemberIds()).isEmpty();
        assertThat(result.recommendations()).singleElement().satisfies(r -> assertThat(r.evidence()).hasSize(1000));
    }

    private Map<String, Object> envelope(List<?> candidates) {
        return Map.of("result", Map.of("candidates", candidates, "evidence_status", "SUFFICIENT"), "origin", "MODEL",
                "usage", List.of(Map.of("request_key", "owner-test-call", "feature", "CHANGE_IMPACT", "failed", false)));
    }

    private long attachSourceDocument(String key) {
        long version = createDocument("API_SPEC");
        api.jdbc.sql("UPDATE design_item_version SET source_document_version_id=:version,source_locator=cast(:locator AS jsonb) WHERE id=:id")
                .param("version", version).param("locator", "{\"location\":\"API 계약\"}")
                .param("id", api.currentVersionId(key)).update();
        return version;
    }

    private long createDocument(String type) {
        long document = api.jdbc.sql("INSERT INTO document(project_id,type,title,created_by) VALUES(:p,:type,'API',:a) RETURNING id")
                .param("p", project).param("type", type).param("a", member).query(Long.class).single();
        long version = api.jdbc.sql("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_at)
                VALUES(:d,1,'APPROVED','fixture.md','hash','기존 내용',:a,now()) RETURNING id
                """).param("d", document).param("a", member).query(Long.class).single();
        api.jdbc.sql("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(:b,:p,:d,:v)")
                .param("b", baseline).param("p", project).param("d", document).param("v", version).update();
        return version;
    }

    private Proposal documentProposal(long document, long version, String key) {
        return proposals.create(caller, project, new Input(Long.toString(baseline), "document", "document change", null, null,
                List.of(), List.of(new DocumentChange(Long.toString(document), Long.toString(version), "수정 내용", null)),
                null, null, null), key);
    }
}
