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
import com.beez.backend.validation.service.ValidationEvidenceBuilder;
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
import org.springframework.context.annotation.Primary;
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
        // 실제 어댑터(PublicationImpactAdapter, -163)가 빈으로 있으므로 이 시험의 기록용 구현을 우선시킨다 — 여기서는 포트 호출 계약만 본다
        @Bean @Primary RecordingImpact recordingImpact(JdbcTemplate jdbc) { return new RecordingImpact(jdbc); }
        @Bean @Primary RecordingIntake recordingIntake(com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository repository) {
            return new RecordingIntake(repository);
        }
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
        final com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository repository;
        RecordingIntake(com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository repository) { this.repository=repository; }
        final List<Long> accepted=new CopyOnWriteArrayList<>(); volatile boolean fail;
        @Override public void accept(long project,long baseline,Long proposal) {
            assertThat(proposal).isNotNull(); accepted.add(baseline);
            repository.accept(project,baseline,proposal);
            if(fail) throw new IllegalStateException("intake failure");
        }
    }
    @Autowired ProposalCommandService commands;
    @Autowired ProposalPublicationService publications;
    @Autowired ProposalQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired ValidationEvidenceBuilder evidenceBuilder;
    @Autowired RecordingImpact impact;
    @Autowired RecordingIntake intake;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationContext context;
    @Autowired com.beez.backend.impactanalysis.PublishedAnalysisProcessor publishedAnalysisProcessor;
    @MockitoSpyBean PublicationRepository repository;
    @MockitoSpyBean RelationStorageService relations;
    @MockitoSpyBean DesignCommandService idempotency;
    @MockitoSpyBean ImpactApprovalGate gate;
    AccessFixtures.ProjectWithLeader project;
    CallerContext leader;
    long baseline, document, version, item, itemVersion, retired, retiredVersion, unchanged;

    @BeforeEach void setup() {
        reset(gate); trusted();
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
    ApproveRequest request(Proposal p) { return new ApproveRequest(p.baseBaselineId(),p.revision(),p.snapshotHash(),null); }
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
        assertThat(count("published_change_analysis")).isZero();
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
        assertThat(count("published_change_analysis")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM published_change_analysis WHERE published_baseline_id=?",String.class,
                Long.parseLong(result.baseline().id()))).isEqualTo("PENDING");
    }

    @Test void changedDocumentWithAnUnmovableCitationIsUnavailableAndMarkedForConfirmationRatherThanInventingNewSource() {
        Proposal proposal=create(true,true,"source-after-publication");
        ready(proposal);
        Publication published=approve(proposal,"publish-source-check");
        long newBaseline=Long.parseLong(published.baseline().id());
        long newDocumentVersion=Long.parseLong(proposal.documents().getFirst().proposedDocumentVersionId());
        long newItemVersion=currentVersion(item);

        assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?",String.class,version))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT document_version_id FROM baseline_document WHERE baseline_id=? AND document_id=?",
                Long.class,newBaseline,document)).isEqualTo(newDocumentVersion);
        assertThat(jdbc.queryForObject("SELECT source_document_version_id FROM design_item_version WHERE id=?",
                Long.class,newItemVersion)).isEqualTo(version);

        var originalSource=Map.of("document_id",Long.toString(document),"document_version_id",Long.toString(version),
                "version_no",1,"location","첫 문단");
        var input=Map.<String,Object>of("id",Long.toString(newItemVersion),"design_item_id",Long.toString(item),"source",originalSource);
        var result=evidenceBuilder.build(project.projectId(),newBaseline,List.of(input));
        assertThat(result.evidence()).isEmpty();
        assertThat(result.unavailableVersionIds()).containsExactly(Long.toString(newItemVersion));
        // 줄 범위 없는 옛 위치("첫 문단")는 새 문서에서 증명할 수 없어 옮기지 않고 확인 필요로 표시한다(-406).
        assertThat(result.needsConfirmationVersionIds()).containsExactly(Long.toString(newItemVersion));
        assertThat(jdbc.queryForObject("SELECT source_status FROM baseline_item_source WHERE baseline_id=? AND design_item_id=?",
                String.class,newBaseline,item)).isEqualTo("NEEDS_CONFIRMATION");
    }
    @Test void publishingClosesOtherDraftsBuiltOnTheSupersededVersionOfTheSameDocument() {
        Proposal winner=create(true,false,"winner"); ready(winner);
        // 같은 기준 버전에서 갈라진 다른 제안의 초안과, 부모가 다른 초안(이미 새 기준에서 만든 것처럼)
        long staleDraft=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,parent_version_id)
                VALUES(?,(SELECT max(version_no)+1 FROM document_version WHERE document_id=?),'DRAFT','stale.md','stale-hash','낡은 초안',?,?) RETURNING id
                """,Long.class,document,document,leader.accountId(),version);
        long otherParent=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,parent_version_id)
                VALUES(?,(SELECT max(version_no)+1 FROM document_version WHERE document_id=?),'DRAFT','other.md','other-hash','다른 부모',?,?) RETURNING id
                """,Long.class,document,document,leader.accountId(),staleDraft);
        long winnerVersion=Long.parseLong(winner.documents().getFirst().proposedDocumentVersionId());
        approve(winner,"winner-approve");
        assertThat(status(winnerVersion)).isEqualTo("APPROVED");
        assertThat(status(version)).isEqualTo("SUPERSEDED");
        assertThat(status(staleDraft)).isEqualTo("REJECTED");
        assertThat(status(otherParent)).isEqualTo("DRAFT");
    }
    String status(long id) { return jdbc.queryForObject("SELECT status FROM document_version WHERE id=?",String.class,id); }
    @Test void documentOnlyAndPublishedRetriesNeverRepublishEvenAfterAnotherBaseline() {
        Proposal p=create(true,false,"doc"); ready(p); clearInvocations(relations);
        var result=approve(p,"key"); assertThat(approve(p,"key")).isEqualTo(result);
        jdbc.update("UPDATE design_http_command SET expires_at=now()-interval '1 second' WHERE project_id=? AND operation LIKE 'proposal:approve:%'",project.projectId());
        assertThat(approve(p,"key").baseline()).isEqualTo(result.baseline());
        long third=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,3,?) RETURNING id",Long.class,project.projectId(),leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",third,project.projectId());
        assertThat(approve(p,"other").baseline()).isEqualTo(result.baseline());
        assertThat(count("baseline")).isEqualTo(3); assertThat(impact.applied).hasSize(1); assertThat(intake.accepted).hasSize(1);
        assertThat(count("published_change_analysis")).isEqualTo(1);
        verify(relations,never()).apply(any(),anyLong(),any());
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+third,p.revision(),p.snapshotHash(),null),"other"),"idempotency_conflict");
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+third,p.revision(),p.snapshotHash(),null),"new"),"version_conflict");
    }

    @Test void publishedDocumentOnlyChangeRemainsNotEvaluatedWithoutPretendingZeroImpact() {
        new TransactionTemplate(transactions).executeWithoutResult(s -> intake.repository.accept(project.projectId(),baseline,null));
        Proposal proposal=create(true,false,"document-analysis"); ready(proposal);
        var result=approve(proposal,"document-analysis-approve");
        long intakeId=jdbc.queryForObject("SELECT id FROM published_change_analysis WHERE published_baseline_id=?",
                Long.class,Long.parseLong(result.baseline().id()));
        publishedAnalysisProcessor.process(intakeId);
        var status=jdbc.queryForMap("SELECT status,evaluation_status,error_code,ai_result FROM published_change_analysis WHERE id=?",intakeId);
        assertThat(status).containsEntry("status","NOT_EVALUATED").containsEntry("evaluation_status","NOT_EVALUATED")
                .containsEntry("error_code","document_change_unavailable");
        assertThat(status.get("ai_result").toString()).isEqualTo("null");
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
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+baseline,2,p.snapshotHash(),null),"old"),"version_conflict");
        error(() -> publications.approve(leader,project.projectId(),Long.parseLong(p.id()),new ApproveRequest(""+baseline,1,"sha256:"+"0".repeat(64),null),"hash"),"version_conflict");
        rolledBack(p);
    }

    @Test void allPublicationFailuresRollbackVersionsRelationsPointerAndIdempotency() {
        Proposal p=create(true,true,"fail"); ready(p);
        impact.fail=true; assertThatThrownBy(() -> approve(p,"fail-impact")).isInstanceOf(IllegalStateException.class); rolledBack(p); impact.fail=false;
        intake.fail=true; assertThatThrownBy(() -> approve(p,"fail-intake")).isInstanceOf(IllegalStateException.class); rolledBack(p); intake.fail=false;
        RelationStorageService relationTarget=AopTestUtils.getUltimateTargetObject(relations);
        doThrow(new IllegalStateException("relation failure")).when(relationTarget).apply(any(),anyLong(),any());
        assertThatThrownBy(() -> approve(p,"fail-relation")).isInstanceOf(IllegalStateException.class); rolledBack(p); reset(relationTarget);
        doThrow(new IllegalStateException("approval failure")).when(repository).publish(anyLong(),anyLong(),anyInt(),anyLong(),anyLong(),anyLong(),any());
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

    // 게이트 판정 자체(상태별)는 ImpactApprovalGateIntegrationTest 가 실제 분석 실행으로 본다. 여기서는 승인·미리 보기 배선만 본다.
    void decide(ImpactApprovalGate.Kind kind,String status,boolean current) {
        var decision=new ImpactApprovalGate.Decision(kind,status,current,"COMPLETED".equals(status) ? null : "analysis_not_started",2,false);
        doReturn(decision).when(gate).evaluate(any(),anyLong(),anyLong()); doReturn(decision).when(gate).peek(any(),anyLong(),anyLong());
    }
    void trusted() { decide(ImpactApprovalGate.Kind.TRUSTED,"COMPLETED",true); }
    Publication approve(Proposal p,String key,String reason) {
        return publications.approve(leader,project.projectId(),Long.parseLong(p.id()),
                new ApproveRequest(p.baseBaselineId(),p.revision(),p.snapshotHash(),reason),key);
    }
    String storedReason(Proposal p) {
        return jdbc.queryForObject("SELECT reason FROM approval WHERE change_proposal_id=?",String.class,Long.parseLong(p.id()));
    }

    @Test void untrustedAnalysisBlocksApprovalUntilTheLeaderLeavesAReasonAndTheReasonIsKept() {
        Proposal p=create(false,true,"gate");
        for (String status : List.of("NOT_EVALUATED","FAILED","INSUFFICIENT_EVIDENCE","STALE")) {
            decide(ImpactApprovalGate.Kind.UNTRUSTED,status,false);
            error(() -> approve(p,"no-reason-"+status),"impact_not_confirmed"); rolledBack(p);
        }
        decide(ImpactApprovalGate.Kind.UNTRUSTED,"COMPLETED",false); // 완료여도 현재 제안과 다르면 신뢰하지 않는다
        error(() -> approve(p,"old-result"),"impact_not_confirmed"); rolledBack(p);
        var pre=preview(p).unreviewed();
        assertThat(pre.requiresOverrideReason()).isTrue(); assertThat(pre.aiCandidates()).isNull();
        assertThat(pre.aiAnalysis().status()).isEqualTo("COMPLETED"); assertThat(pre.aiAnalysis().isCurrent()).isFalse();
        var result=approve(p,"with-reason","  분석 서버 장애로 직접 확인함  ");
        assertThat(result.proposal().status()).isEqualTo("PUBLISHED");
        assertThat(storedReason(p)).isEqualTo("분석 서버 장애로 직접 확인함");
        assertThat(queries.approval(leader,project.projectId(),Long.parseLong(p.id())).impactOverrideReason()).isEqualTo("분석 서버 장애로 직접 확인함");
    }

    @Test void runningAnalysisCannotBeOverriddenAndTrustedAnalysisIgnoresAReason() {
        Proposal running=create(false,true,"running");
        decide(ImpactApprovalGate.Kind.RUNNING,"RUNNING",true);
        error(() -> approve(running,"r","그래도 승인"),"analysis_running"); rolledBack(running);
        var pre=preview(running).unreviewed(); assertThat(pre.requiresOverrideReason()).isFalse(); assertThat(pre.aiCandidates()).isNull();
        assertThat(pre.aiAnalysis().status()).isEqualTo("RUNNING");
        trusted();
        var trustedPreview=preview(running).unreviewed();
        assertThat(trustedPreview.requiresOverrideReason()).isFalse(); assertThat(trustedPreview.aiCandidates()).isEqualTo(2);
        assertThat(approve(running,"t","필요 없는 사유").proposal().status()).isEqualTo("PUBLISHED");
        assertThat(storedReason(running)).isNull();
        assertThat(queries.approval(leader,project.projectId(),Long.parseLong(running.id())).impactOverrideReason()).isNull();
    }

    @Test void overrideReasonMustBeNonBlankAndAtMost1000Characters() {
        Proposal p=create(false,true,"reason");
        decide(ImpactApprovalGate.Kind.UNTRUSTED,"NOT_EVALUATED",false);
        for (String bad : List.of(" ","\n","x".repeat(1001)))
            assertThatThrownBy(() -> approve(p,"bad",bad)).isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        rolledBack(p);
        assertThat(approve(p,"max","x".repeat(1000)).proposal().status()).isEqualTo("PUBLISHED");
    }

    @Test void previewHttpResponseKeepsNullAiCandidatesAndTheNewFields() {
        Proposal p=create(false,true,"http-null");
        var client=client().login(project.leaderEmail(),AccessFixtures.PASSWORD);
        String path="/api/projects/"+project.projectId()+"/change-proposals/"+p.id()+"/approval-preview";
        decide(ImpactApprovalGate.Kind.UNTRUSTED,"NOT_EVALUATED",false);
        var untrusted=client.get(path);
        assertThat(untrusted.statusCode()).isEqualTo(200);
        assertThat(untrusted.body()).contains("\"ai_candidates\":null","\"requires_override_reason\":true",
                "\"ai_analysis\":{\"status\":\"NOT_EVALUATED\"","\"is_current\":false","\"reanalysis_in_progress\":false","\"error_code\":\"analysis_not_started\"");
        trusted();
        var ok=client.get(path);
        assertThat(ok.body()).contains("\"ai_candidates\":2","\"requires_override_reason\":false").doesNotContain("\"error_code\"");
    }

    @Test void gateDoesNotRunForAnAlreadyPublishedProposalReplay() {
        Proposal p=create(false,true,"replay"); approve(p,"first");
        decide(ImpactApprovalGate.Kind.UNTRUSTED,"FAILED",false);
        clearInvocations(gate);
        assertThat(approve(p,"first").proposal().status()).isEqualTo("PUBLISHED");  // 멱등 재생
        assertThat(approve(p,"second").proposal().status()).isEqualTo("PUBLISHED"); // 이미 발행된 제안의 재요청
        verify(gate,never()).evaluate(any(),anyLong(),anyLong());
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
        // 실제 어댑터(-163)가 빈으로 있고, 이 시험에서는 기록용 구현이 우선한다 (승인 경로 전체는 ProposalPublicationImpactIntegrationTest)
        assertThat(context.getBeansOfType(ValidationImpactPort.class).values())
                .anyMatch(port -> port instanceof com.beez.backend.validation.service.PublicationImpactAdapter);
        assertThat(context.getBean(ValidationImpactPort.class)).isInstanceOf(RecordingImpact.class);
        assertThat(context.getBean(PostPublishAnalysisIntake.class)).isInstanceOf(RecordingIntake.class);
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

    // ---- S15P31A108-406: 문서가 바뀐 기준에서 설계 항목의 유효 출처 ----
    static final String SOURCE_DOC = "# 제목\n첫줄\n둘째줄\n셋째줄\n넷째줄\n";

    /** 세 항목이 문서의 서로 다른 줄을 인용하게 한다(항목 버전의 출처는 승인 뒤에도 바꿀 수 있는 칼럼이다). */
    void citeLines(long documentVersion) {
        jdbc.update("UPDATE document_version SET content_text=? WHERE id=?",SOURCE_DOC,documentVersion);
        cite(item,documentVersion,2,3); cite(retired,documentVersion,2,2); cite(unchanged,documentVersion,4,5);
    }
    void cite(long designItem,long documentVersion,int start,int end) {
        jdbc.update("""
                UPDATE design_item_version SET source_document_version_id=?,source_locator=cast(? AS jsonb)
                WHERE design_item_id=?
                """,documentVersion,"{\"type\":\"text_lines\",\"start_line\":"+start+",\"end_line\":"+end
                +",\"location\":\"요구사항 / L"+start+"-L"+end+"\"}",designItem);
    }
    Proposal createDocument(long baseBaseline,long documentId,long baseVersion,String after,String key) {
        return commands.create(leader,project.projectId(),new Input(""+baseBaseline,"변경","이유",null,null,List.of(),
                List.of(new DocumentChange(""+documentId,""+baseVersion,after,null)),null,null,null),key);
    }
    Map<String,Object> sourceRow(long baselineId,long designItem) {
        var rows=jdbc.queryForList("""
                SELECT source_document_version_id,source_locator::text AS locator,source_status,remapped_from_document_version_id
                FROM baseline_item_source WHERE baseline_id=? AND design_item_id=?
                """,baselineId,designItem);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    Map<String,Object> effective(long baselineId,long designItem) {
        return jdbc.queryForMap("""
                SELECT source_document_version_id,source_locator::text AS locator,source_status
                FROM baseline_item_effective_source WHERE baseline_id=? AND design_item_id=?
                """,baselineId,designItem);
    }
    Map<String,Object> locator(Map<String,Object> row) { return JSON.readValue(row.get("locator").toString(),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}); }

    @Test void documentChangeMovesCitedLinesWithoutTouchingItemVersions() {
        citeLines(version);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","doc-lines"); ready(p);
        Publication published=approve(p,"doc-lines-approve");
        long next=Long.parseLong(published.baseline().id());
        long newDocumentVersion=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());

        // 인용한 두 줄이 한 줄 밀려 그대로 있으므로 옮긴다. 줄 번호와 표시 문자열이 함께 바뀐다.
        var moved=sourceRow(next,item);
        assertThat(moved).containsEntry("source_status","REMAPPED").containsEntry("remapped_from_document_version_id",version);
        assertThat(((Number)moved.get("source_document_version_id")).longValue()).isEqualTo(newDocumentVersion);
        assertThat(locator(moved)).containsEntry("start_line",3).containsEntry("end_line",4).containsEntry("location","요구사항 / L3-L4");
        // 둘째 항목의 마지막 줄(넷째줄)이 수정돼 위치를 증명할 수 없다. 옛 위치는 보존하고 문서 버전만 따라간다.
        var unconfirmed=sourceRow(next,unchanged);
        assertThat(unconfirmed).containsEntry("source_status","NEEDS_CONFIRMATION");
        assertThat(((Number)unconfirmed.get("source_document_version_id")).longValue()).isEqualTo(newDocumentVersion);
        assertThat(locator(unconfirmed)).containsEntry("start_line",4).containsEntry("end_line",5);
        // 항목 버전의 출처는 승인 뒤 불변이다 — 옛 문서 버전을 그대로 가리킨다. 옛 기준에는 행이 없다.
        assertThat(jdbc.queryForObject("SELECT source_document_version_id FROM design_item_version WHERE id=?",Long.class,itemVersion))
                .isEqualTo(version);
        assertThat(sourceRow(baseline,item)).isNull();
        // 읽는 쪽이 보는 뷰는 기준별 유효 출처를 돌려준다.
        assertThat(effective(next,item)).containsEntry("source_status","REMAPPED");
        assertThat(((Number)effective(next,item).get("source_document_version_id")).longValue()).isEqualTo(newDocumentVersion);
        assertThat(effective(baseline,item)).containsEntry("source_status",null);
        assertThat(((Number)effective(baseline,item).get("source_document_version_id")).longValue()).isEqualTo(version);
    }

    @Test void remappedSourceFeedsEvidenceAndAnUnmovedOneIsReportedForConfirmation() {
        citeLines(version);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","doc-evidence"); ready(p);
        long next=Long.parseLong(approve(p,"doc-evidence-approve").baseline().id());
        long newDocumentVersion=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());
        var source=Map.<String,Object>of("document_id",Long.toString(document),"document_version_id",Long.toString(newDocumentVersion),
                "version_no",2,"location","요구사항 / L3-L4");
        var ok=Map.<String,Object>of("id",Long.toString(itemVersion),"design_item_id",Long.toString(item),"source",source);
        var result=evidenceBuilder.build(project.projectId(),next,List.of(ok));
        assertThat(result.unavailableVersionIds()).isEmpty();
        assertThat(result.evidence()).singleElement().satisfies(e -> {
            assertThat(e).containsEntry("text","첫줄\n둘째줄\n");
            assertThat((Map<String,Object>)e.get("locator")).containsEntry("start_line",3).containsEntry("end_line",4);
        });

        long unchangedVersion=currentVersion(unchanged);
        var unconfirmedSource=Map.<String,Object>of("document_id",Long.toString(document),"document_version_id",Long.toString(newDocumentVersion),
                "version_no",2,"location","요구사항 / L4-L5");
        var needs=Map.<String,Object>of("id",Long.toString(unchangedVersion),"design_item_id",Long.toString(unchanged),"source",unconfirmedSource);
        var blocked=evidenceBuilder.build(project.projectId(),next,List.of(needs));
        assertThat(blocked.evidence()).isEmpty();
        assertThat(blocked.unavailableVersionIds()).containsExactly(Long.toString(unchangedVersion));
        assertThat(blocked.needsConfirmationVersionIds()).containsExactly(Long.toString(unchangedVersion));
    }

    @Test void unrelatedPublicationCarriesTheEffectiveSourceForward() {
        citeLines(version);
        long other=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'API_SPEC','API',?) RETURNING id",
                Long.class,project.projectId(),leader.accountId());
        long otherVersion=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','other-fixture.md','other-before','다른 문서\n',?,?,now()) RETURNING id
                """,Long.class,other,leader.accountId(),leader.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)",baseline,project.projectId(),other,otherVersion);
        Proposal first=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","carry-1"); ready(first);
        long second=Long.parseLong(approve(first,"carry-1-approve").baseline().id());

        // 이 문서와 무관한 다른 문서만 바꾸는 발행: 옮겨 둔 출처가 옛 문서 버전으로 되돌아가면 안 된다.
        Proposal unrelated=createDocument(second,other,otherVersion,"다른 문서 수정\n","carry-2"); ready(unrelated);
        long third=Long.parseLong(approve(unrelated,"carry-2-approve").baseline().id());
        assertThat(sourceRow(third,item)).containsEntry("source_status","REMAPPED");
        assertThat(locator(sourceRow(third,item))).containsEntry("start_line",3).containsEntry("end_line",4);
        assertThat(sourceRow(third,unchanged)).containsEntry("source_status","NEEDS_CONFIRMATION");
        assertThat(effective(third,item).get("locator").toString()).isEqualTo(sourceRow(second,item).get("locator").toString());
    }

    @Test void secondDocumentChangeStartsFromThePreviousEffectiveSource() {
        citeLines(version);
        Proposal first=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","chain-1"); ready(first);
        long second=Long.parseLong(approve(first,"chain-1-approve").baseline().id());
        long v2=Long.parseLong(first.documents().getFirst().proposedDocumentVersionId());
        // 두 번째 변경에서 맨 앞에 두 줄이 더 생긴다. 첫 항목은 3~4줄 → 5~6줄. 이미 확인 필요인 항목은 다시 옮기지 않는다.
        Proposal again=createDocument(second,document,v2,"머리 1\n머리 2\n# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","chain-2"); ready(again);
        long third=Long.parseLong(approve(again,"chain-2-approve").baseline().id());
        long v3=Long.parseLong(again.documents().getFirst().proposedDocumentVersionId());
        var moved=sourceRow(third,item);
        assertThat(moved).containsEntry("source_status","REMAPPED").containsEntry("remapped_from_document_version_id",v2);
        assertThat(((Number)moved.get("source_document_version_id")).longValue()).isEqualTo(v3);
        assertThat(locator(moved)).containsEntry("start_line",5).containsEntry("end_line",6);
        var stillUnconfirmed=sourceRow(third,unchanged);
        assertThat(stillUnconfirmed).containsEntry("source_status","NEEDS_CONFIRMATION").containsEntry("remapped_from_document_version_id",version);
        assertThat(((Number)stillUnconfirmed.get("source_document_version_id")).longValue()).isEqualTo(v3);
        assertThat(locator(stillUnconfirmed)).containsEntry("start_line",4).containsEntry("end_line",5);
    }

    @Test void itemUpdateInTheSameProposalAlsoGetsTheMovedSource() {
        citeLines(version);
        Proposal p=create(true,true,"mixed-lines");
        // create() 는 본문을 "새 본문 …" 한 줄로 바꾸므로 인용 줄을 옮길 수 없다 — 업데이트된 항목도 같은 규칙을 따른다.
        ready(p);
        long next=Long.parseLong(approve(p,"mixed-lines-approve").baseline().id());
        assertThat(sourceRow(next,item)).containsEntry("source_status","NEEDS_CONFIRMATION");
        assertThat(sourceRow(next,unchanged)).containsEntry("source_status","NEEDS_CONFIRMATION");
        assertThat(sourceRow(next,retired)).isNull(); // 퇴역한 항목은 새 기준에 없다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline_item_source WHERE baseline_id=?",Integer.class,next)).isEqualTo(2);
    }

    /** S15P31A108-415: 근거 없이 채택해 출처 위치가 없는 항목이 있어도 그 문서를 바꾸는 제안이 발행된다. */
    @Test void documentChangePublishesWhenAnItemHasASourceDocumentButNoLocator() {
        citeLines(version);
        jdbc.update("UPDATE design_item_version SET source_locator=NULL WHERE design_item_id=?",unchanged);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄\n","doc-no-locator"); ready(p);
        long next=Long.parseLong(approve(p,"doc-no-locator-approve").baseline().id());
        long newDocumentVersion=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());

        // 옮길 줄이 없으므로 확인 필요로 두고 문서 버전만 따라간다. 위치는 빈 객체다(백필 V52 와 같은 값).
        var noLocator=sourceRow(next,unchanged);
        assertThat(noLocator).containsEntry("source_status","NEEDS_CONFIRMATION").containsEntry("remapped_from_document_version_id",version);
        assertThat(((Number)noLocator.get("source_document_version_id")).longValue()).isEqualTo(newDocumentVersion);
        assertThat(locator(noLocator)).isEmpty();
        // 위치가 있는 다른 항목은 평소대로 옮겨진다.
        assertThat(sourceRow(next,item)).containsEntry("source_status","REMAPPED");

        // 다음 발행(같은 문서를 또 고침)에서도 막히지 않고 확인 필요가 이어진다.
        Proposal again=createDocument(next,document,newDocumentVersion,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄\n끝줄\n","doc-no-locator-2"); ready(again);
        long third=Long.parseLong(approve(again,"doc-no-locator-2-approve").baseline().id());
        assertThat(sourceRow(third,unchanged)).containsEntry("source_status","NEEDS_CONFIRMATION").containsEntry("remapped_from_document_version_id",version);
        assertThat(locator(sourceRow(third,unchanged))).isEmpty();
    }

    @Test void publishingWithoutADocumentChangeWritesNoSourceRows() {
        citeLines(version);
        Proposal p=create(false,true,"items-only");
        long next=Long.parseLong(approve(p,"items-only-approve").baseline().id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline_item_source WHERE baseline_id=?",Integer.class,next)).isZero();
    }

    @Test void backfillMigrationFillsAnAlreadyDriftedCurrentBaselineAndIsIdempotent() throws Exception {
        citeLines(version);
        long v2=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,2,'APPROVED','v2.md','v2-hash',?,?,?,now()) RETURNING id
                """,Long.class,document,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n",leader.accountId(),leader.accountId());
        jdbc.update("UPDATE document_version SET status='SUPERSEDED' WHERE id=?",version);
        // -406 이전에 발행된 기준처럼: 기준의 문서는 v2 인데 항목은 옛 v1 출처를 그대로 가리킨다.
        long drifted=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",
                Long.class,project.projectId(),leader.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)",drifted,project.projectId(),document,v2);
        for (long designItem : List.of(item,retired,unchanged))
            jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)",drifted,project.projectId(),designItem,currentVersion(designItem));
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",drifted,project.projectId());
        assertThat(count("baseline_item_source")).isZero();

        runBackfill(); runBackfill();
        var moved=sourceRow(drifted,item);
        assertThat(moved).containsEntry("source_status","REMAPPED").containsEntry("remapped_from_document_version_id",version);
        assertThat(((Number)moved.get("source_document_version_id")).longValue()).isEqualTo(v2);
        assertThat(locator(moved)).containsEntry("start_line",3).containsEntry("end_line",4).containsEntry("location","요구사항 / L3-L4");
        assertThat(sourceRow(drifted,unchanged)).containsEntry("source_status","NEEDS_CONFIRMATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline_item_source WHERE baseline_id=?",Integer.class,drifted)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline_item_source WHERE baseline_id=?",Integer.class,baseline)).isZero();
    }

    void runBackfill() throws Exception {
        var migration=new db.migration.V52__backfill_baseline_item_source();
        try (var connection=jdbc.getDataSource().getConnection()) {
            connection.setAutoCommit(true);
            migration.migrate(new org.flywaydb.core.api.migration.Context() {
                @Override public org.flywaydb.core.api.configuration.Configuration getConfiguration() { return null; }
                @Override public java.sql.Connection getConnection() { return connection; }
            });
        }
    }

    @Autowired com.beez.backend.changeproposal.repository.DraftContextRepository drafts;
    @Autowired com.beez.backend.changeproposal.repository.ProposalRepository proposalRepository;

    @Test void draftAndCandidateReadersFollowTheEffectiveSourceAfterADocumentChange() {
        citeLines(version);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","readers"); ready(p);
        long next=Long.parseLong(approve(p,"readers-approve").baseline().id());
        long newDocumentVersion=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());
        // 다음 문서 수정 초안은 새 문서 버전에서 나온 항목을 찾아야 한다. 옛 버전으로는 더 이상 찾지 않는다.
        assertThat(drafts.relatedItemCount(next,newDocumentVersion)).isEqualTo(3);
        assertThat(drafts.relatedItemCount(next,version)).isZero();
        var related=drafts.relatedItems(next,newDocumentVersion,10);
        assertThat(related).hasSize(3);
        assertThat(related).filteredOn(r -> "NEEDS_CONFIRMATION".equals(r.get("source_status"))).singleElement()
                .satisfies(r -> assertThat(((Number)r.get("source_document_version_id")).longValue()).isEqualTo(newDocumentVersion));
        assertThat(related).filteredOn(r -> "REMAPPED".equals(r.get("source_status"))).hasSize(2)
                .allSatisfy(r -> assertThat(r.get("source_locator").toString()).contains("\"start_line\": 3").doesNotContain("\"start_line\": 2"));
        // 영향 후보의 DOCUMENT 갈래도 문서 변경 뒤 사라지지 않는다.
        assertThat(proposalRepository.candidates(project.projectId(),next,Set.of(),Set.of(document),100))
                .anyMatch(c -> "DOCUMENT".equals(c.targetType()) && c.targetId().equals(""+document));
    }

    @Autowired com.beez.backend.designitem.service.DesignItemCommandService designItems;

    /**
     * S15P31A108-422: 옮기지 못한 출처를 팀장이 확정하면 근거로 쓰이고, 문서가 같은 다음 발행에는 확정한 사람째로 이어지며,
     * 문서가 다시 바뀌면 다른 위치처럼 옮겨진다. 이어받지 못하면 확정한 위치가 사라져 검증이 다시 막힌다.
     */
    @Test void leaderConfirmedSourceFeedsEvidenceIsCarriedAndMovesWithTheDocument() {
        citeLines(version);
        long other=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'API_SPEC','API',?) RETURNING id",
                Long.class,project.projectId(),leader.accountId());
        long otherVersion=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','other-fixture.md','other-before','다른 문서\n',?,?,now()) RETURNING id
                """,Long.class,other,leader.accountId(),leader.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)",baseline,project.projectId(),other,otherVersion);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","confirm-1"); ready(p);
        long second=Long.parseLong(approve(p,"confirm-1-approve").baseline().id());
        long v2=Long.parseLong(p.documents().getFirst().proposedDocumentVersionId());
        long unchangedVersion=currentVersion(unchanged);
        assertThat(sourceRow(second,unchanged)).containsEntry("source_status","NEEDS_CONFIRMATION");

        // PR #299 리뷰: 문서만 바뀌면 항목 버전은 그대로라, 옛 원문(v1)을 보고 고른 줄은 거절해야 새 원문에 엉뚱한 줄이 저장되지 않는다.
        assertThatThrownBy(() -> designItems.confirmSource(leader,unchanged,new com.beez.backend.designitem.dto.ConfirmDesignItemSourceRequest(
                ""+project.projectId(),""+unchangedVersion,""+version,4,5,null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("source_document_changed"));
        assertThat(sourceRow(second,unchanged)).containsEntry("source_status","NEEDS_CONFIRMATION");
        var confirmed=designItems.confirmSource(leader,unchanged,new com.beez.backend.designitem.dto.ConfirmDesignItemSourceRequest(
                ""+project.projectId(),""+unchangedVersion,""+v2,5,6,null));
        assertThat(confirmed.source().location()).isEqualTo("제목 / L5-L6");
        assertThat(sourceRow(second,unchanged)).containsEntry("source_status","CONFIRMED").containsEntry("remapped_from_document_version_id",v2);
        var source=Map.<String,Object>of("document_id",""+document,"document_version_id",""+v2,"version_no",2,"location","제목 / L5-L6");
        var result=evidenceBuilder.build(project.projectId(),second,
                List.of(Map.of("id",""+unchangedVersion,"design_item_id",""+unchanged,"source",source)));
        assertThat(result.unavailableVersionIds()).isEmpty();
        assertThat(result.evidence()).singleElement().satisfies(e -> assertThat(e).containsEntry("text","셋째줄\n넷째줄 수정\n"));
        // 확정은 같은 문서 버전에서 위치만 바꿀 수 있다 — 출처 문서 버전을 바꾸는 갱신은 트리거가 막는다.
        assertThatThrownBy(() -> jdbc.update("UPDATE baseline_item_source SET source_document_version_id=? WHERE baseline_id=? AND design_item_id=?",
                version,second,unchanged)).isInstanceOf(org.springframework.dao.DataAccessException.class);

        Proposal unrelated=createDocument(second,other,otherVersion,"다른 문서 수정\n","confirm-2"); ready(unrelated);
        long third=Long.parseLong(approve(unrelated,"confirm-2-approve").baseline().id());
        assertThat(sourceRow(third,unchanged)).containsEntry("source_status","CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT confirmed_by FROM baseline_item_source WHERE baseline_id=? AND design_item_id=?",
                Long.class,third,unchanged)).isEqualTo(leader.accountId());

        Proposal again=createDocument(third,document,v2,"머리\n# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","confirm-3"); ready(again);
        long fourth=Long.parseLong(approve(again,"confirm-3-approve").baseline().id());
        var moved=sourceRow(fourth,unchanged);
        assertThat(moved).containsEntry("source_status","REMAPPED");
        assertThat(locator(moved)).containsEntry("start_line",6).containsEntry("end_line",7).containsEntry("location","제목 / L6-L7");
    }

    /** S15P31A108-422: 인용 없음으로 확인된 항목은 문서가 바뀌어도 확인 필요가 되지 않고, 근거 수집이 수동 항목처럼 건너뛴다. */
    @Test void noQuoteSourceFollowsTheDocumentWithoutBlockingEvidence() {
        citeLines(version);
        jdbc.update("UPDATE design_item_version SET source_locator=cast(? AS jsonb) WHERE design_item_id=?",
                com.beez.backend.designitem.support.SourceLocators.NO_QUOTE,unchanged);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","no-quote"); ready(p);
        long next=Long.parseLong(approve(p,"no-quote-approve").baseline().id());
        var row=sourceRow(next,unchanged);
        assertThat(row).containsEntry("source_status","REMAPPED");
        assertThat(locator(row)).containsEntry("no_quote",true);
        var result=evidenceBuilder.build(project.projectId(),next,
                List.of(Map.of("id",""+currentVersion(unchanged),"design_item_id",""+unchanged)));
        assertThat(result.unavailableVersionIds()).isEmpty();
        assertThat(result.evidence()).isEmpty();
    }

    @Test void sourceRowsAreImmutable() {
        citeLines(version);
        Proposal p=createDocument(baseline,document,version,"# 제목\n새 줄\n첫줄\n둘째줄\n셋째줄\n넷째줄 수정\n","immutable"); ready(p);
        long next=Long.parseLong(approve(p,"immutable-approve").baseline().id());
        assertThatThrownBy(() -> jdbc.update("UPDATE baseline_item_source SET source_status='REMAPPED' WHERE baseline_id=?",next))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
