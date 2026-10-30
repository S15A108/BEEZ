package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.beez.backend.designitem.support.DesignJson.JSON;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.changeproposal.port.*;
import com.beez.backend.changeproposal.repository.PublicationRepository;
import com.beez.backend.changeproposal.service.*;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.service.DesignCommandService;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.relation.service.RelationStorageService;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.AopTestUtils;

@Import({ProposalIntegrationTest.Support.class, ProposalPublicationIntegrationTest.Ports.class})
@TestPropertySource(properties = "beez.document.embedding-profile=publication-test")
class ProposalPublicationIntegrationTest extends IntegrationTestSupport {
    @TestConfiguration static class Ports {
        @Bean RecordingImpact recordingImpact(JdbcTemplate jdbc) { return new RecordingImpact(jdbc); }
        @Bean RecordingIntake recordingIntake() { return new RecordingIntake(); }
    }
    static class RecordingImpact implements ValidationImpactPort {
        final JdbcTemplate jdbc;
        RecordingImpact(JdbcTemplate jdbc) { this.jdbc=jdbc; }
        final List<ImpactRequest> applied=new CopyOnWriteArrayList<>();
        volatile boolean fail;
        volatile Runnable onPreview=() -> {};
        @Override public ImpactResult applyPublication(CallerContext caller,long project,ImpactRequest request) {
            assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?",Long.class,project))
                    .isEqualTo(request.previousBaselineId());
            assertThat(request.baselineId()).isNotNull().isNotEqualTo(request.previousBaselineId());
            assertThat(request.changedItems()).allMatch(i -> Set.of("UPDATE","RETIRE").contains(i.operation()));
            applied.add(request); if(fail) throw new IllegalStateException("impact failure"); return ImpactResult.empty();
        }
        @Override public ImpactResult previewPublication(long project,ImpactRequest request) {
            assertThat(request.baselineId()).isNull();
            assertThat(request.changedItems()).allMatch(i -> i.newVersionId()==null);
            onPreview.run(); return ImpactResult.empty();
        }
    }
    static class RecordingIntake implements PostPublishAnalysisIntake {
        final List<Long> accepted=new CopyOnWriteArrayList<>(); volatile boolean fail;
        @Override public void accept(long project,long baseline,Long proposal) {
            assertThat(proposal).isNotNull(); accepted.add(baseline);
            if(fail) throw new IllegalStateException("intake failure");
        }
    }
    @Autowired ProposalCommandService commands;
    @Autowired ProposalPublicationService publications;
    @Autowired ProposalQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordingImpact impact;
    @Autowired RecordingIntake intake;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    @MockitoSpyBean PublicationRepository repository;
    @MockitoSpyBean RelationStorageService relations;
    @MockitoSpyBean DesignCommandService idempotency;
    AccessFixtures.ProjectWithLeader project;
    CallerContext leader;
    long baseline, document, version, item, itemVersion, retired, retiredVersion, unchanged;

    @BeforeEach void setup() {
        impact.applied.clear(); impact.fail=false; impact.onPreview=() -> {}; intake.accepted.clear(); intake.fail=false;
        project=fixtures().projectWithLeader(); leader=CallerContext.human(project.leaderAccountId());
        baseline=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",Long.class,project.projectId(),leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",baseline,project.projectId());
        document=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id",Long.class,project.projectId(),leader.accountId());
        version=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','fixture.md','before','이전 본문',?,?,now()) RETURNING id
                """,Long.class,document,leader.accountId(),leader.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)",baseline,project.projectId(),document,version);
        item=baseItem("REQ-001"); itemVersion=currentVersion(item);
        retired=baseItem("REQ-002"); retiredVersion=currentVersion(retired); unchanged=baseItem("REQ-003");
        jdbc.update("""
                INSERT INTO design_item_dependency(project_id,from_item_id,to_item_id,origin,status,confirmed_by,confirmed_at,reason)
                VALUES(?,?,?,'EXPLICIT','CONFIRMED',?,now(),'근거')
                """,project.projectId(),item,retired,leader.accountId());
    }
    long baseItem(String key) {
        long id=jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,'REQUIREMENT') RETURNING id",Long.class,project.projectId(),key);
        long v=jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,
                  source_document_version_id,source_locator,approved_at)
                VALUES(?,1,'APPROVED','기존',1,'{"description":"기존"}','before','HUMAN',?,'{"location":"첫 문단"}',now()) RETURNING id
                """,Long.class,id,version);
        jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)",baseline,project.projectId(),id,v); return id;
    }
    long currentVersion(long id) { return jdbc.queryForObject("SELECT id FROM design_item_version WHERE design_item_id=? ORDER BY version_no DESC LIMIT 1",Long.class,id); }
    Proposal create(boolean docs,boolean mixed,String key) {
        return create(leader,docs,mixed,key);
    }
    Proposal create(CallerContext author,boolean docs,boolean mixed,String key) {
        var changes=mixed ? List.of(new Item("UPDATE",""+item,""+itemVersion,"REQUIREMENT",1,Map.of("description","보완")),
                new Item("RETIRE",""+retired,""+retiredVersion,null,null,null),
                new Item("CREATE",null,null,"POLICY",1,Map.of("description","신규 정책"))) : List.<Item>of();
        var documents=docs ? List.of(new DocumentChange(""+document,""+version,"새 본문 "+key,null)) : List.<DocumentChange>of();
        return commands.create(author,project.projectId(),new Input(""+baseline,"변경","이유",null,null,changes,documents,null,null,null),key);
    }
    ApproveRequest request(Proposal p) { return new ApproveRequest(p.baseBaselineId(),p.revision(),p.snapshotHash()); }
    Publication approve(Proposal p,String key) { return publications.approve(leader,project.projectId(),Long.parseLong(p.id()),request(p),key); }
    Preview preview(Proposal p) { return publications.preview(leader,project.projectId(),Long.parseLong(p.id())); }
    void ready(Proposal p) {
        long v=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());
        jdbc.update("UPDATE document_processing SET status='COMPLETED',source_status='READY',index_status='READY' WHERE document_version_id=?",v);
        jdbc.update("""
                INSERT INTO document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                SELECT document_version_id,attempt,text_hash,'publication-test','test','test',1 FROM document_processing WHERE document_version_id=?
                """,v);
        jdbc.update("INSERT INTO document_chunk(document_version_id,chunk_no,text,embedding) VALUES(?,0,'내용',cast(? AS vector))",v,"[1,"+"0,".repeat(1022)+"0]");
    }
    void error(Runnable run,String code) { assertThatThrownBy(run::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo(code)); }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE project_id=?",Integer.class,project.projectId()); }
    String state(Proposal p) { return jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?",String.class,Long.parseLong(p.id())); }
    void rolledBack(Proposal p) {
        assertThat(count("baseline")).isEqualTo(1); assertThat(state(p)).isEqualTo("PROPOSED");
        assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?",Long.class,project.projectId())).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval WHERE change_proposal_id=?",Integer.class,Long.parseLong(p.id()))).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM design_item_version WHERE id=?",String.class,itemVersion)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT retired_at IS NULL FROM design_item WHERE id=?",Boolean.class,retired)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM design_item_dependency WHERE from_item_id=? AND to_item_id=?",String.class,item,retired)).isEqualTo("CONFIRMED");
        assertThat(count("design_item_dependency_history")).isZero(); assertThat(count("design_relation_set")).isZero();
        assertThat(count("design_http_command")).isEqualTo(1); // only the original proposal command
        assertThat(count("design_item")).isEqualTo(3);
        if (!p.documents().isEmpty()) {
            assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?",String.class,version)).isEqualTo("APPROVED");
            assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?",String.class,Long.parseLong(p.documents().getFirst().proposedDocumentVersionId()))).isEqualTo("DRAFT");
        }
    }

    @Test void mixedPublicationPreservesHistoryCopiesReferencesAndRechecksRelations() {
        jdbc.update("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin)
                VALUES(?,5,'REJECTED','거절',1,'{"description":"거절"}','hash','HUMAN')
                """,item);
        Proposal p=create(true,true,"mixed"); ready(p);
        var before=preview(p); assertThat(before.nextBaselineVersionNo()).isEqualTo(2);
        assertThat(before.unchanged()).isEqualTo(new Counts(1,0));
        assertThat(before.baselineItems()).filteredOn(i -> "UPDATE".equals(i.operation())).singleElement()
                .satisfies(i -> assertThat(i.versionNo()).isEqualTo(6));
        assertThat(before.baselineItems()).filteredOn(i -> "CREATE".equals(i.operation())).singleElement().satisfies(i -> {
            assertThat(i.designItemId()).isNull(); assertThat(i.proposalItemIndex()).isEqualTo(2); assertThat(i.versionNo()).isEqualTo(1);
        });
        clearInvocations(relations);
        Publication result=approve(p,"publish");
        assertThat(result.proposal().status()).isEqualTo("PUBLISHED"); assertThat(result.baseline().items()).hasSize(3);
        assertThat(result.baseline().versionNo()).isEqualTo(2);
        assertThat(result.baseline().items()).noneMatch(i -> i.designItemId().equals(""+retired));
        assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?",String.class,version)).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?",String.class,version)).isEqualTo("이전 본문");
        assertThat(jdbc.queryForObject("SELECT source_document_version_id FROM design_item_version WHERE id=?",Long.class,currentVersion(item)))
                .isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT source_locator->>'location' FROM design_item_version WHERE id=?",String.class,currentVersion(item)))
                .isEqualTo("첫 문단");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline_item WHERE baseline_id=?",Integer.class,baseline)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM design_item_version WHERE id=?",String.class,retiredVersion)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM design_item_dependency WHERE from_item_id=? AND to_item_id=?",String.class,item,retired)).isEqualTo("NEEDS_RECHECK");
        assertThat(queries.approval(leader,project.projectId(),Long.parseLong(p.id())).baselineId()).isEqualTo(result.baseline().id());
        verify(relations,times(1)).apply(eq(leader),eq(project.projectId()),argThat(r -> new HashSet<>(r.recheckItemIds()).equals(Set.of(item,retired))));
        assertThat(impact.applied).hasSize(1); assertThat(impact.applied.getFirst().changedItems()).hasSize(2);
        assertThat(impact.applied.getFirst().changedItems()).anySatisfy(i -> {
            assertThat(i.operation()).isEqualTo("RETIRE"); assertThat(i.newVersionId()).isNull();
        });
        assertThat(intake.accepted).containsExactly(Long.parseLong(result.baseline().id()));
    }

    @Test void documentOnlyAndPublishedRetriesNeverRepublishEvenAfterAnotherBaseline() {
        Proposal p=create(true,false,"doc"); ready(p); clearInvocations(relations);
        var result=approve(p,"key"); assertThat(approve(p,"key")).isEqualTo(result);
        jdbc.update("UPDATE design_http_command SET expires_at=now()-interval '1 second' WHERE project_id=? AND operation LIKE 'proposal:approve:%'",project.projectId());
        assertThat(approve(p,"key").baseline()).isEqualTo(result.baseline());
        long third=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,3,?) RETURNING id",Long.class,project.projectId(),leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",third,project.projectId());
        assertThat(approve(p,"other").baseline()).isEqualTo(result.baseline());
        assertThat(count("baseline")).isEqualTo(3); assertThat(impact.applied).hasSize(1); assertThat(intake.accepted).hasSize(1);
        verify(relations,never()).apply(any(),anyLong(),any());
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+third,p.revision(),p.snapshotHash()),"other"),"idempotency_conflict");
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+third,p.revision(),p.snapshotHash()),"new"),"version_conflict");
    }

    @Test void indexFailuresAndStaleInputsLeaveNothingPublished() {
        Proposal p=create(true,false,"index"); error(() -> approve(p,"p"),"analysis_not_completed");
        error(() -> preview(p),"analysis_not_completed"); ready(p);
        long proposed=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());
        for (String sql : List.of("embedding_profile_id='old'","attempt=99","chunk_count=2")) {
            jdbc.update("UPDATE document_index_manifest SET "+sql+" WHERE document_version_id=?",proposed);
            error(() -> approve(p,"p"),"analysis_not_completed");
            jdbc.update("UPDATE document_index_manifest SET embedding_profile_id='publication-test',attempt=1,chunk_count=1 WHERE document_version_id=?",proposed);
        }
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+baseline,2,p.snapshotHash()),"old"),"version_conflict");
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+baseline,1,"sha256:"+"0".repeat(64)),"hash"),"version_conflict");
        rolledBack(p);
    }

    @Test void allPublicationFailuresRollbackVersionsRelationsPointerAndIdempotency() {
        Proposal p=create(true,true,"fail"); ready(p);
        impact.fail=true; assertThatThrownBy(() -> approve(p,"fail-impact")).isInstanceOf(IllegalStateException.class); rolledBack(p); impact.fail=false;
        intake.fail=true; assertThatThrownBy(() -> approve(p,"fail-intake")).isInstanceOf(IllegalStateException.class); rolledBack(p); intake.fail=false;
        RelationStorageService relationTarget=AopTestUtils.getUltimateTargetObject(relations);
        doThrow(new IllegalStateException("relation failure")).when(relationTarget).apply(any(),anyLong(),any());
        assertThatThrownBy(() -> approve(p,"fail-relation")).isInstanceOf(IllegalStateException.class); rolledBack(p); reset(relationTarget);
        doThrow(new IllegalStateException("approval failure")).when(repository).publish(anyLong(),anyLong(),anyInt(),anyLong(),anyLong(),anyLong());
        assertThatThrownBy(() -> approve(p,"fail-approval")).isInstanceOf(RuntimeException.class).hasMessageContaining("approval failure"); rolledBack(p); reset(repository);
        doThrow(new IllegalStateException("idempotency failure")).when(idempotency).save(anyLong(),anyLong(),contains("approve"),anyString(),any(),any());
        assertThatThrownBy(() -> approve(p,"fail-idempotency")).isInstanceOf(IllegalStateException.class); rolledBack(p);
    }

    @Test void concurrentApprovalsPublishExactlyOnce() throws Exception {
        Proposal p=create(false,true,"race"); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> { start.await(); return approve(p,"a"); });
            var b=pool.submit(() -> { start.await(); return approve(p,"b"); }); start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS).baseline()).isEqualTo(b.get(15,TimeUnit.SECONDS).baseline());
        }
        assertThat(count("baseline")).isEqualTo(2); assertThat(impact.applied).hasSize(1); assertThat(intake.accepted).hasSize(1);
    }

    @Test void previewDoesNotWriteOrAllocateIdsAndCountsMultiTargetRequests() {
        Proposal p=create(false,true,"preview");
        long other=fixtures().account(); fixtures().member(project.projectId(),other,ProjectRole.MEMBER);
        long request=comment(p,leader.accountId(),"REVIEW_REQUEST");
        jdbc.update("INSERT INTO change_comment_target VALUES(?,?,now()),(?,?,now())",request,leader.accountId(),request,other);
        assertThat(preview(p).unreviewed().openReviewRequests()).isEqualTo(1);
        comment(p,leader.accountId(),"COMMENT"); assertThat(preview(p).unreviewed().openReviewRequests()).isEqualTo(1);
        comment(p,other,"COMMENT"); assertThat(preview(p).unreviewed().openReviewRequests()).isZero();
        int commandCount=count("design_http_command");
        var sequence=jdbc.queryForMap("SELECT last_value,is_called FROM design_item_id_seq");
        var baselineSequence=jdbc.queryForMap("SELECT last_value,is_called FROM baseline_id_seq");
        preview(p); preview(p);
        assertThat(jdbc.queryForMap("SELECT last_value,is_called FROM design_item_id_seq")).isEqualTo(sequence);
        assertThat(jdbc.queryForMap("SELECT last_value,is_called FROM baseline_id_seq")).isEqualTo(baselineSequence);
        assertThat(count("design_http_command")).isEqualTo(commandCount); assertThat(count("baseline")).isEqualTo(1);
        assertThat(count("design_item_dependency_history")).isZero(); assertThat(impact.applied).isEmpty(); assertThat(intake.accepted).isEmpty();
    }
    long comment(Proposal p,long author,String kind) {
        return jdbc.queryForObject("INSERT INTO change_comment(change_proposal_id,proposal_revision_no,author_account_id,kind,body) VALUES(?,?,?,?, '의견') RETURNING id",Long.class,Long.parseLong(p.id()),p.revision(),author,kind);
    }

    @Test void readSnapshotRemainsConsistentWhenACommentArrivesDuringPreview() throws Exception {
        Proposal p=create(false,true,"snapshot");
        impact.onPreview=() -> {
            try(var pool=Executors.newSingleThreadExecutor()) {
                pool.submit(() -> {
                    long c=comment(p,leader.accountId(),"REVIEW_REQUEST");
                    jdbc.update("INSERT INTO change_comment_target VALUES(?,?,now())",c,leader.accountId());
                }).get(10,TimeUnit.SECONDS);
            } catch(Exception e) { throw new IllegalStateException(e); }
        };
        assertThat(preview(p).unreviewed().openReviewRequests()).isZero();
        impact.onPreview=() -> {};
        assertThat(preview(p).unreviewed().openReviewRequests()).isEqualTo(1);
    }

    @Test void authorityStateAndHttpContractAreEnforced() {
        Proposal p=create(false,true,"auth");
        long member=fixtures().account(); fixtures().member(project.projectId(),member,ProjectRole.MEMBER);
        error(() -> publications.approve(CallerContext.human(member),project.projectId(),Long.parseLong(p.id()),request(p),"m"),"forbidden");
        error(() -> publications.preview(CallerContext.human(member),project.projectId(),Long.parseLong(p.id())),"forbidden");
        long token=fixtures().agentToken(project.projectId(),leader.accountId());
        error(() -> publications.approve(CallerContext.agent(leader.accountId(),token),project.projectId(),Long.parseLong(p.id()),request(p),"a"),"forbidden");
        error(() -> publications.preview(CallerContext.human(fixtures().account()),project.projectId(),Long.parseLong(p.id())),"not_found");
        var client=client().login(project.leaderEmail(),AccessFixtures.PASSWORD);
        String path="/api/projects/"+project.projectId()+"/change-proposals/"+p.id(); String body=JSON.writeValueAsString(request(p));
        assertThat(client.get(path+"/approval-preview").statusCode()).isEqualTo(200);
        assertThat(client.post(path+"/approve",body,"Idempotency-Key","http").statusCode()).isEqualTo(403);
        assertThat(client.post(path+"/approve",body,"Origin","http://localhost:5173").statusCode()).isEqualTo(400);
        var response=client.post(path+"/approve",body,"Origin","http://localhost:5173","Idempotency-Key","http");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("\"proposal\"","\"baseline\"","PUBLISHED");
        error(() -> preview(p),"invalid_transition");
        assertThat(context.getBeansOfType(ValidationImpactPort.class)).hasSize(1);
        assertThat(context.getBeansOfType(PostPublishAnalysisIntake.class)).hasSize(1);
    }

    @Test void staleBaselineAndRejectedOrWithdrawnProposalsAreRejected() {
        Proposal rejected=create(false,true,"rejected"),withdrawn=create(false,true,"withdrawn"),stale=create(false,true,"stale");
        commands.reject(leader,project.projectId(),Long.parseLong(rejected.id()),new RejectRequest("사유",rejected.revision(),rejected.snapshotHash()),"reject");
        commands.withdraw(leader,project.projectId(),Long.parseLong(withdrawn.id()),new WithdrawRequest(withdrawn.revision(),withdrawn.snapshotHash()),"withdraw");
        error(() -> approve(rejected,"r"),"invalid_transition"); error(() -> approve(withdrawn,"w"),"invalid_transition");
        long next=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",Long.class,project.projectId(),leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",next,project.projectId());
        error(() -> approve(stale,"s"),"baseline_conflict"); error(() -> preview(stale),"baseline_conflict");
    }

    @Test void delegatedLeaderCannotPublishAfterWaitingForProjectLock() throws Exception {
        Proposal p=create(false,true,"delegate");
        long successor=fixtures().account(), member=fixtures().member(project.projectId(),successor,ProjectRole.MEMBER);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var waiting=new java.util.concurrent.atomic.AtomicReference<Future<Publication>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE",Long.class,project.projectId());
                waiting.set(pool.submit(() -> approve(p,"delegate")));
                awaitLock("%FROM project WHERE id%FOR UPDATE%");
                jdbc.update("UPDATE project_member SET role='MEMBER' WHERE id=?",project.leaderMemberId());
                jdbc.update("UPDATE project_member SET role='LEADER' WHERE id=?",member);
            });
            assertThatThrownBy(() -> waiting.get().get(15,TimeUnit.SECONDS)).cause().isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getCode()).isEqualTo("forbidden"));
        }
        rolledBack(p);
    }

    @Test void commentShareLockAndPublicationSerializeWithoutDeadlock() throws Exception {
        Proposal p=create(false,true,"comment-race");
        try(var pool=Executors.newSingleThreadExecutor()) {
            var waiting=new java.util.concurrent.atomic.AtomicReference<Future<Publication>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM change_proposal WHERE id=? FOR SHARE",Long.class,Long.parseLong(p.id()));
                waiting.set(pool.submit(() -> approve(p,"publish-after-comment")));
                awaitLock("%UPDATE change_proposal SET status='PUBLISHED'%");
                comment(p,leader.accountId(),"COMMENT");
            });
            assertThat(waiting.get().get(15,TimeUnit.SECONDS).proposal().status()).isEqualTo("PUBLISHED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_comment WHERE change_proposal_id=?",Integer.class,Long.parseLong(p.id()))).isEqualTo(1);
    }

    @Test void rejectedResubmissionRejectsOldRevisionAndKeepsBothDecisionRows() {
        Proposal first=create(false,true,"first");
        commands.reject(leader,project.projectId(),Long.parseLong(first.id()),new RejectRequest("보완",first.revision(),first.snapshotHash()),"reject");
        var next=new Input(""+baseline,"보완","새 사유",null,null,first.items(),List.of(),first.revision(),first.snapshotHash(),null);
        Proposal second=commands.resubmit(leader,project.projectId(),Long.parseLong(first.id()),next,"resubmit");
        error(() -> approve(first,"old-revision"),"version_conflict");
        commands.startReview(leader,project.projectId(),Long.parseLong(second.id()),new WithdrawRequest(second.revision(),second.snapshotHash()),"review");
        assertThat(approve(second,"second").proposal().revision()).isEqualTo(2);
        assertThat(queries.revisions(leader,project.projectId(),Long.parseLong(first.id()),null,20).items())
                .extracting(Revision::decision).containsExactly("APPROVED","REJECTED");
    }

    @Test void databaseUniqueIndexRejectsDuplicatePublicationOfOneProposal() {
        Proposal p=create(false,true,"unique"); approve(p,"publish");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO baseline(project_id,version_no,change_proposal_id,published_by) VALUES(?,3,?,?)",
                project.projectId(),Long.parseLong(p.id()),leader.accountId()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        assertThat(count("baseline")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void publishedVersionsKeepHumanOrAgentAuthorSeparateFromApprovingLeader(boolean agent) {
        long author=fixtures().account(); fixtures().member(project.projectId(),author,ProjectRole.MEMBER);
        CallerContext submitter=agent ? CallerContext.agent(author,fixtures().agentToken(project.projectId(),author)) : CallerContext.human(author);
        Proposal p=create(submitter,false,true,"author");
        var published=approve(p,"publish-author");
        assertVersionAuthors(published,author,agent ? "AI_PROPOSED" : "HUMAN");
        assertThat(published.baseline().approvedBy()).isEqualTo(leader.accountId().toString());
        assertThat(queries.approval(leader,project.projectId(),Long.parseLong(p.id())).approvedBy()).isEqualTo(leader.accountId().toString());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicationUsesCurrentRevisionOriginAfterSwitchingSubmissionChannel(boolean agentFirst) {
        long author=fixtures().account(); fixtures().member(project.projectId(),author,ProjectRole.MEMBER);
        long token=fixtures().agentToken(project.projectId(),author);
        CallerContext human=CallerContext.human(author), agent=CallerContext.agent(author,token);
        Proposal p=create(agentFirst ? agent : human,false,true,"channel-first");
        commands.reject(leader,project.projectId(),Long.parseLong(p.id()),new RejectRequest("보완",p.revision(),p.snapshotHash()),"channel-reject");
        var input=new Input(p.baseBaselineId(),"보완","보완 사유",null,null,p.items(),List.of(),p.revision(),p.snapshotHash(),null);
        Proposal revised=commands.resubmit(agentFirst ? human : agent,project.projectId(),Long.parseLong(p.id()),input,"channel-resubmit");
        assertVersionAuthors(approve(revised,"channel-publish"),author,agentFirst ? "HUMAN" : "AI_PROPOSED");
    }

    void assertVersionAuthors(Publication publication,long author,String origin) {
        var changed=publication.baseline().items().stream().filter(i -> !i.designItemId().equals(""+unchanged)).toList();
        assertThat(changed).hasSize(2); // UPDATE and CREATE, not the unchanged or retired version.
        for(var ref : changed) {
            var row=jdbc.queryForMap("SELECT created_by,origin FROM design_item_version WHERE id=?",Long.parseLong(ref.designItemVersionId()));
            assertThat(((Number)row.get("created_by")).longValue()).isEqualTo(author);
            assertThat(row.get("origin")).isEqualTo(origin);
        }
    }

    void awaitLock(String queryPattern) {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)",Boolean.class,queryPattern))) return;
            try { Thread.sleep(20); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
        throw new AssertionError("Expected waiting SQL lock: "+queryPattern);
    }
}
