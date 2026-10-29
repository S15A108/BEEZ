package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.*;
import static com.beez.backend.designitem.support.DesignJson.JSON;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalQueryService;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.beez.backend.document.storage.DocumentStorage;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(ProposalIntegrationTest.Support.class)
class ProposalIntegrationTest extends IntegrationTestSupport {
    /** Object storage stand-in: the test endpoint is a closed port, and we need to see which originals remain. */
    static class MemoryStorage implements DocumentStorage {
        final Map<String, byte[]> objects=new ConcurrentHashMap<>();
        @Override public String store(byte[] bytes, String extension) { String key="proposal-test/"+UUID.randomUUID()+"."+extension; objects.put(key,bytes.clone()); return key; }
        @Override public byte[] read(String key) throws IOException { byte[] bytes=objects.get(key); if(bytes==null) throw new IOException("missing "+key); return bytes.clone(); }
        @Override public void delete(String key) { objects.remove(key); }
    }
    @TestConfiguration static class Support {
        @Bean @Primary MemoryStorage memoryStorage() { return new MemoryStorage(); }
    }
    @Autowired MemoryStorage storage;
    @Autowired ProposalCommandService commands;
    @Autowired ProposalQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    AccessFixtures.ProjectWithLeader project;
    CallerContext author;
    long baseline, document, version, item, itemVersion;
    static final String ORIGIN = "http://localhost:5173";

    @BeforeEach void setup() {
        storage.objects.clear();
        project=fixtures().projectWithLeader(); author=CallerContext.human(project.leaderAccountId());
        baseline=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class, project.projectId(), author.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        document=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id",
                Long.class, project.projectId(), author.accountId());
        version=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','fixture.md','hash-before','첫째\n이전\n끝\n',?,?,now()) RETURNING id
                """, Long.class, document, author.accountId(), author.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project.projectId(), document, version);
        item=jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,'REQ-001','REQUIREMENT') RETURNING id", Long.class, project.projectId());
        itemVersion=jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,source_document_version_id,approved_at)
                VALUES(?,1,'APPROVED','요구사항',1,'{"description":"기존"}','item-hash','HUMAN',?,now()) RETURNING id
                """, Long.class, item, version);
        jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)", baseline, project.projectId(), item, itemVersion);
    }

    Input input(String after) {
        return new Input(""+baseline,"문서 보완","반영 이유",null,null,List.of(),
                List.of(new DocumentChange(""+document,""+version,after,null)),null,null,null);
    }
    Input resubmission(Proposal previous, String after) {
        Input value=input(after);
        return new Input(value.baseBaselineId(), value.title(), value.reason(), null,null,value.items(),value.documents(),previous.revision(),previous.snapshotHash(),null);
    }
    Proposal create(String key) { return commands.create(author,project.projectId(),input("첫째\n수정\n끝\n"),key); }
    void reject(Proposal p) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE", Long.class,project.projectId());
            jdbc.update("""
                    INSERT INTO approval(change_proposal_id,proposal_revision_no,decided_by,decision,reason,base_baseline_id)
                    VALUES(?, ?, ?, 'REJECTED','근거를 보완해 주세요.',?)
                    """,Long.parseLong(p.id()),p.revision(),author.accountId(),baseline);
            jdbc.update("UPDATE change_proposal SET status='REJECTED' WHERE id=?",Long.parseLong(p.id()));
        });
    }
    void error(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test void createReplaysAndDoesNotPublishOrMutateApprovedContent() {
        Proposal p=create("same");
        assertThat(create("same")).isEqualTo(p);
        error(() -> commands.create(author,project.projectId(),input("다른 본문"),"same"),"idempotency_conflict");
        assertThat(p.revision()).isEqualTo(1);
        assertThat(p.status()).isEqualTo("PROPOSED");
        assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?",Long.class,project.projectId())).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?",String.class,version)).contains("이전");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal_revision WHERE change_proposal_id=?",Integer.class,Long.parseLong(p.id()))).isEqualTo(1);
    }

    @Test void submittedAndReviewingContentIsLockedAndRejectionResubmitsSameId() {
        Proposal first=create("initial"); long id=Long.parseLong(first.id());
        Input next=resubmission(first,"두번째 보완");
        error(() -> commands.resubmit(author,project.projectId(),id,next,"retry"),"proposal_locked");
        jdbc.update("UPDATE change_proposal SET status='IN_REVIEW' WHERE id=?",id);
        error(() -> commands.resubmit(author,project.projectId(),id,next,"retry"),"proposal_locked");
        reject(first);
        Proposal second=commands.resubmit(author,project.projectId(),id,next,"retry");
        assertThat(second.id()).isEqualTo(first.id()); assertThat(second.revision()).isEqualTo(2);
        assertThat(commands.resubmit(author,project.projectId(),id,next,"retry")).isEqualTo(second);
        assertThat(queries.detail(author,project.projectId(),id,1).proposal().documents().getFirst().afterContent()).contains("수정");
        assertThat(queries.detail(author,project.projectId(),id,1).proposal().status()).isEqualTo("REJECTED");
        var history=queries.revisions(author,project.projectId(),id,null,20).items();
        assertThat(history).hasSize(2); assertThat(history.get(1).decisionReason()).isEqualTo("근거를 보완해 주세요.");
        reject(second);
        error(() -> commands.resubmit(author,project.projectId(),id,next,"stale"),"version_conflict");
        assertThat(commands.resubmit(author,project.projectId(),id,resubmission(second,"세번째 보완"),"third").revision()).isEqualTo(3);
    }

    long taskWithPlanRef(String key) {
        // Task CHECKs require a current plan (and a started validation for COMPLETED), so statuses are set afterwards.
        long task=jdbc.queryForObject("INSERT INTO task(project_id,key,title,status) VALUES(?,?,?,'DRAFT') RETURNING id",
                Long.class,project.projectId(),key,key);
        long plan=jdbc.queryForObject("""
                INSERT INTO task_plan_revision(task_id,revision_no,title,goal,input_hash,created_by_account_id)
                VALUES(?,1,?,?,'h',?) RETURNING id
                """,Long.class,task,key,key,author.accountId());
        jdbc.update("UPDATE task SET current_plan_revision_id=? WHERE id=?",plan,task);
        jdbc.update("INSERT INTO task_plan_design_ref(plan_revision_id,design_item_id,design_item_version_id,relation) VALUES(?,?,?,'READS')",
                plan,item,itemVersion);
        return task;
    }
    long validationFor(long task, long plan) {
        long validation=jdbc.queryForObject("""
                INSERT INTO validation(task_id,plan_revision_id,baseline_id,status,trigger,input_snapshot_hash,rule_version)
                VALUES(?,?,?,'PASS','AGENT','h','1') RETURNING id
                """,Long.class,task,plan,baseline);
        jdbc.update("INSERT INTO validation_design_ref(validation_id,design_item_id,design_item_version_id,relation) VALUES(?,?,?,'READS')",
                validation,item,itemVersion);
        return validation;
    }
    long currentPlan(long task) { return jdbc.queryForObject("SELECT current_plan_revision_id FROM task WHERE id=?",Long.class,task); }

    @Test void explicitCandidatesSkipClosedTasksAndValidationsOfOldPlans() {
        long open=taskWithPlanRef("T-open");
        long done=taskWithPlanRef("T-done");
        long cancelled=taskWithPlanRef("T-cancelled");
        long oldPlan=currentPlan(open);
        long oldValidation=validationFor(open,oldPlan);
        long newPlan=jdbc.queryForObject("""
                INSERT INTO task_plan_revision(task_id,revision_no,title,goal,input_hash,created_by_account_id)
                VALUES(?,2,'T-open','T-open','h2',?) RETURNING id
                """,Long.class,open,author.accountId());
        jdbc.update("INSERT INTO task_plan_design_ref(plan_revision_id,design_item_id,design_item_version_id,relation) VALUES(?,?,?,'READS')",
                newPlan,item,itemVersion);
        jdbc.update("UPDATE task SET current_plan_revision_id=? WHERE id=?",newPlan,open);
        long currentValidation=validationFor(open,newPlan);
        long doneValidation=validationFor(done,currentPlan(done));
        jdbc.update("UPDATE task SET status='READY' WHERE id=?",open);
        jdbc.update("UPDATE task SET status='COMPLETED',started_validation_id=? WHERE id=?",doneValidation,done);
        jdbc.update("UPDATE task SET status='CANCELLED' WHERE id=?",cancelled);
        Proposal p=create("candidates");
        var candidates=queries.detail(author,project.projectId(),Long.parseLong(p.id()),null).explicitCandidates();
        assertThat(candidates).filteredOn(c -> c.targetType().equals("TASK")).extracting(c -> c.targetId())
                .containsExactly(""+open);
        assertThat(candidates).filteredOn(c -> c.targetType().equals("VALIDATION")).extracting(c -> c.targetId())
                .containsExactly(""+currentValidation)
                .doesNotContain(""+oldValidation,""+doneValidation);
    }

    @Test void approvalRevisionHasNoDefaultSoDecisionsMustNameTheReviewedRevision() {
        assertThat(jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='approval' AND column_name='proposal_revision_no'
                """,String.class)).isNull();
    }

    @Test void textChangeBecomesDraftVersionWithIndexJobInTheSameCommit() {
        Proposal p=commands.create(author,project.projectId(),input("첫째\r\n수정\r\n끝\r\n"),"text-version");
        var change=p.documents().getFirst();
        assertThat(change.proposedDocumentVersionId()).isNotBlank();
        assertThat(change.afterContent()).isEqualTo("첫째\n수정\n끝\n"); // normalized exactly like a DOC-A12 upload
        long created=Long.parseLong(change.proposedDocumentVersionId());
        var row=jdbc.queryForMap("SELECT * FROM document_version WHERE id=?",created);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("version_no")).isEqualTo(2);
        assertThat(((Number)row.get("parent_version_id")).longValue()).isEqualTo(version);
        assertThat(((Number)row.get("created_by")).longValue()).isEqualTo(author.accountId());
        assertThat(((Number)row.get("change_proposal_id")).longValue()).isEqualTo(Long.parseLong(p.id()));
        assertThat(row.get("content_text")).isEqualTo("첫째\n수정\n끝\n");
        assertThat(row.get("content_hash")).isEqualTo(change.contentHash());
        String storageKey=(String)row.get("storage_key");
        assertThat(storageKey).endsWith(".md");
        assertThat(storage.objects.get(storageKey)).isEqualTo("첫째\n수정\n끝\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var job=jdbc.queryForMap("SELECT status,text_hash FROM document_processing WHERE document_version_id=?",created);
        assertThat(job.get("status")).isEqualTo("PENDING");
        assertThat(job.get("text_hash")).isEqualTo(change.contentHash());
        assertThat(jdbc.queryForObject("SELECT proposed_document_version_id FROM change_proposal_document WHERE change_proposal_id=?",
                Long.class,Long.parseLong(p.id()))).isEqualTo(created);
        assertThat(queries.detail(author,project.projectId(),Long.parseLong(p.id()),null).proposal().documents().getFirst()
                .proposedDocumentVersionId()).isEqualTo(""+created);
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?",String.class,version)).contains("이전");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_processing WHERE document_version_id=?",Integer.class,version)).isZero();
    }

    @Test void replayStoresNoAnotherVersionOrOriginal() {
        Proposal p=create("replay-text");
        assertThat(create("replay-text")).isEqualTo(p);
        assertThat(storage.objects).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=?",Integer.class,document)).isEqualTo(2);
    }

    @Test void resubmissionStoresANewVersionAndKeepsEarlierRevisionsVersion() {
        Proposal first=create("v1"); long id=Long.parseLong(first.id()); reject(first);
        Proposal second=commands.resubmit(author,project.projectId(),id,resubmission(first,"두번째 본문"),"v2");
        String firstVersion=first.documents().getFirst().proposedDocumentVersionId();
        String secondVersion=second.documents().getFirst().proposedDocumentVersionId();
        assertThat(secondVersion).isNotBlank().isNotEqualTo(firstVersion);
        assertThat(queries.detail(author,project.projectId(),id,1).proposal().documents().getFirst().proposedDocumentVersionId()).isEqualTo(firstVersion);
        assertThat(storage.objects).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE change_proposal_id=? AND status='DRAFT'",Integer.class,id)).isEqualTo(2);
    }

    @Test void invalidOrUnauthorizedTextIsRejectedBeforeAnyOriginalIsStored() {
        error(() -> commands.create(author,project.projectId(),input("제어\u0001문자"),"control"),"validation_failed");
        error(() -> commands.create(author,project.projectId(),input("  \n"),"blank"),"validation_failed");
        error(() -> commands.create(CallerContext.human(fixtures().projectWithLeader().leaderAccountId()),project.projectId(),input("외부"),"foreign"),"not_found");
        Input missing=new Input(""+baseline,"제목","사유",null,null,List.of(),
                List.of(new DocumentChange("999999999",""+version,"본문",null)),null,null,null);
        error(() -> commands.create(author,project.projectId(),missing,"missing-document"),"not_found");
        var empty=fixtures().projectWithLeader();
        error(() -> commands.create(CallerContext.human(empty.leaderAccountId()),empty.projectId(),input("변경"),"no-baseline"),"baseline_missing");
        assertThat(storage.objects).isEmpty();
        Proposal p=create("locked-base"); long id=Long.parseLong(p.id());
        int stored=storage.objects.size();
        error(() -> commands.resubmit(author,project.projectId(),id,resubmission(p,"잠긴 제안"),"locked"),"proposal_locked");
        Input value=resubmission(p,"오래된 요청");
        error(() -> commands.resubmit(author,project.projectId(),id,new Input(value.baseBaselineId(),value.title(),value.reason(),null,null,
                value.items(),value.documents(),9,value.expectedSnapshotHash(),null),"stale"),"version_conflict");
        assertThat(storage.objects).hasSize(stored);
    }

    @Test void textEqualToBaseAfterNormalizationIsNoChangeAndLeavesNothingBehind() {
        error(() -> commands.create(author,project.projectId(),input("첫째\r\n이전\r\n끝\r\n"),"same-as-base"),"validation_failed");
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=?",Integer.class,document)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal WHERE project_id=?",Integer.class,project.projectId())).isZero();
    }

    @Test void unchangedDocumentAmongRealChangesIsRejectedBeforeAnyOriginalIsStored() {
        long other=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'API_SPEC','API 명세',?) RETURNING id",
                Long.class,project.projectId(),author.accountId());
        long otherVersion=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','api.md','api-hash','API\n그대로\n',?,?,now()) RETURNING id
                """,Long.class,other,author.accountId(),author.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)",baseline,project.projectId(),other,otherVersion);
        Input mixed=new Input(""+baseline,"문서 둘","사유",null,null,List.of(),List.of(
                new DocumentChange(""+document,""+version,"첫째\n수정\n끝\n",null),
                new DocumentChange(""+other,""+otherVersion,"API\r\n그대로\r\n",null)),null,null,null);
        error(() -> commands.create(author,project.projectId(),mixed,"mixed"),"validation_failed");
        assertThat(storage.objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id IN (?,?)",Integer.class,document,other)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal WHERE project_id=?",Integer.class,project.projectId())).isZero();
    }

    @Test void documentOnlyChangeAndExplicitCandidatesHaveStableBaseDiffAfterBaselineMoves() {
        Proposal p=create("diff");
        var detail=queries.detail(author,project.projectId(),Long.parseLong(p.id()),null);
        assertThat(detail.comparisons().getFirst().diff()).contains("-이전\n+수정\n");
        assertThat(detail.explicitCandidates()).anyMatch(c -> c.targetType().equals("DESIGN_ITEM") && c.targetId().equals(""+item));
        long newVersion=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,approved_at)
                VALUES(?,(SELECT max(version_no)+1 FROM document_version WHERE document_id=?),'APPROVED','new.md','new-hash','별도 승인 내용',now()) RETURNING id
                """,Long.class,document,document);
        long next=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",Long.class,project.projectId(),author.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)",next,project.projectId(),document,newVersion);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",next,project.projectId());
        var moved=queries.detail(author,project.projectId(),Long.parseLong(p.id()),null);
        assertThat(moved.proposal().requiresRefresh()).isTrue();
        assertThat(moved.comparisons().getFirst().currentChanged()).isTrue();
        assertThat(moved.comparisons().getFirst().diff()).isEqualTo(detail.comparisons().getFirst().diff());
        error(() -> create("new-key"),"baseline_conflict");
    }

    @Test void historicalRejectionDoesNotInheritLaterPublicationOrAnotherSubmissionActor() {
        Proposal first=create("human"); reject(first);
        long token=fixtures().agentToken(project.projectId(),author.accountId());
        Proposal second=commands.resubmit(CallerContext.agent(author.accountId(),token),project.projectId(),Long.parseLong(first.id()),
                resubmission(first,"에이전트가 보완"),"agent-resubmit");
        assertThat(second.actorType()).isEqualTo("AGENT");
        long published=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",
                Long.class,project.projectId(),author.accountId());
        jdbc.update("""
                INSERT INTO approval(change_proposal_id,proposal_revision_no,decided_by,decision,base_baseline_id,result_baseline_id)
                VALUES(?,2,?,'APPROVED',?,?)
                """,Long.parseLong(first.id()),author.accountId(),baseline,published);
        jdbc.update("UPDATE change_proposal SET status='PUBLISHED',published_baseline_id=? WHERE id=?",published,Long.parseLong(first.id()));
        var old=queries.detail(author,project.projectId(),Long.parseLong(first.id()),1).proposal();
        assertThat(old.status()).isEqualTo("REJECTED");
        assertThat(old.publishedBaselineId()).isNull();
        assertThat(old.actorType()).isEqualTo("HUMAN");
        var latest=queries.detail(author,project.projectId(),Long.parseLong(first.id()),2).proposal();
        assertThat(latest.status()).isEqualTo("PUBLISHED");
        assertThat(latest.publishedBaselineId()).isEqualTo(""+published);
        assertThat(latest.actorType()).isEqualTo("AGENT");
    }

    @Test void uploadedDraftIsClaimedOnceAndRemainsImmutable() {
        long draft=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,parent_version_id)
                VALUES(?,2,'DRAFT','draft.md','draft-hash','파일 수정본',?,?) RETURNING id
                """,Long.class,document,author.accountId(),version);
        Input value=new Input(""+baseline,"파일 변경","수정 사유",null,null,List.of(),
                List.of(new DocumentChange(""+document,""+version,null,""+draft)),null,null,null);
        Proposal p=commands.create(author,project.projectId(),value,"upload");
        assertThat(p.documents().getFirst().afterContent()).isEqualTo("파일 수정본");
        error(() -> commands.create(author,project.projectId(),value,"other"),"document_already_proposed");
        assertThatThrownBy(() -> jdbc.update("UPDATE document_version SET content_text='변조' WHERE id=?",draft))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE change_proposal_revision SET snapshot='{}' WHERE change_proposal_id=?",Long.parseLong(p.id())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM change_proposal_revision WHERE change_proposal_id=?",Long.parseLong(p.id())))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE change_proposal SET reason='변조' WHERE id=?",Long.parseLong(p.id())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void foreignReferencesAndNonAuthorAreRejectedAndAgentUsesSameCommand() {
        var foreign=fixtures().projectWithLeader();
        error(() -> commands.create(CallerContext.human(foreign.leaderAccountId()),project.projectId(),input("변경"),"foreign"),"not_found");
        Proposal p=create("author"); reject(p);
        long member=fixtures().account(); fixtures().member(project.projectId(),member,ProjectRole.MEMBER);
        error(() -> commands.resubmit(CallerContext.human(member),project.projectId(),Long.parseLong(p.id()),resubmission(p,"변경"),"other"),"forbidden");
        long token=fixtures().agentToken(project.projectId(),member);
        Proposal agent=commands.create(CallerContext.agent(member,token),project.projectId(),input("에이전트 수정안"),"agent");
        assertThat(agent.actorType()).isEqualTo("AGENT");
        assertThat(agent.documents().getFirst().proposedDocumentVersionId()).isNotBlank();
        fixtures().revokeToken(token);
        error(() -> commands.create(CallerContext.agent(member,token),project.projectId(),input("에이전트 수정안"),"agent"),"token_revoked");
        long outsiderDoc=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','외부',?) RETURNING id",Long.class,foreign.projectId(),foreign.leaderAccountId());
        Input bad=new Input(""+baseline,"외부 참조","사유",null,null,List.of(),List.of(new DocumentChange(""+outsiderDoc,""+version,"본문",null)),null,null,null);
        error(() -> commands.create(author,project.projectId(),bad,"cross-doc"),"not_found");
    }

    @Test void itemSchemaIsStoredForImpactAnalysisAndRejectsNoChangeOrBadSchema() {
        Item update=new Item("UPDATE",""+item,""+itemVersion,null,1,Map.of("description","변경"));
        Input value=new Input(""+baseline,"항목 변경","사유",null,null,List.of(update),List.of(),null,null,null);
        Proposal p=commands.create(author,project.projectId(),value,"item");
        assertThat(p.items().getFirst().kind()).isEqualTo("REQUIREMENT");
        assertThat(jdbc.queryForObject("SELECT schema_version FROM change_proposal_item WHERE change_proposal_id=?",Integer.class,Long.parseLong(p.id()))).isEqualTo(1);
        error(() -> commands.create(author,project.projectId(),input("첫째\n이전\n끝\n"),"no-change"),"validation_failed");
        Input invalid=new Input(""+baseline,"항목 변경","사유",null,null,
                List.of(new Item("CREATE",null,null,"API_CONTRACT",1,Map.of("description","잘못된 계약"))),List.of(),null,null,null);
        error(() -> commands.create(author,project.projectId(),invalid,"invalid"),"validation_failed");
    }

    @Test void listUsesScopedCursorAndRevisionHistoryIsPaged() {
        create("one"); Proposal newest=create("two");
        var first=queries.list(author,project.projectId(),"PROPOSED",null,null,1);
        assertThat(first.items().getFirst().id()).isEqualTo(newest.id()); assertThat(first.hasMore()).isTrue();
        assertThat(queries.list(author,project.projectId(),"PROPOSED",null,first.nextCursor(),1).items()).hasSize(1);
        error(() -> queries.list(author,project.projectId(),"REJECTED",null,first.nextCursor(),1),"validation_failed");
        error(() -> queries.list(author,project.projectId(),null,null,"broken",1),"validation_failed");
        reject(newest); commands.resubmit(author,project.projectId(),Long.parseLong(newest.id()),resubmission(newest,"재제출"),"retry");
        var history=queries.revisions(author,project.projectId(),Long.parseLong(newest.id()),null,1);
        assertThat(history.hasMore()).isTrue();
        assertThat(queries.revisions(author,project.projectId(),Long.parseLong(newest.id()),history.nextBeforeRevision(),1).items().getFirst().decision()).isEqualTo("REJECTED");
    }

    @Test void httpContractChecksOriginAndReturnsKoreanConflict() {
        var client=client().login(project.leaderEmail(),AccessFixtures.PASSWORD);
        String path="/api/projects/"+project.projectId()+"/change-proposals";
        String body=JSON.writeValueAsString(input("HTTP 수정"));
        assertThat(client.post(path,body,"Idempotency-Key","http").statusCode()).isEqualTo(403);
        var response=client.post(path,body,"Origin",ORIGIN,"Idempotency-Key","http");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        Proposal p=JSON.readValue(response.body(),Proposal.class);
        assertThat(client.get(path+"/"+p.id()).statusCode()).isEqualTo(200);
        var locked=client.send(client.request(path+"/"+p.id(),"Origin",ORIGIN,"Idempotency-Key","put","Content-Type","application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(resubmission(p,"다시 제출")))));
        assertThat(locked.statusCode()).isEqualTo(409); assertThat(locked.body()).contains("proposal_locked","반려");
        assertThat(client.get(path+"?limit=0").statusCode()).isEqualTo(400);
    }

    @Test void concurrentCreatesWithSameKeyReturnOneProposal() throws Exception {
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Proposal> task=() -> {start.await(); return create("race");};
            Future<Proposal> a=pool.submit(task), b=pool.submit(task); start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal WHERE project_id=?",Integer.class,project.projectId())).isEqualTo(1);
            assertThat(storage.objects).hasSize(1);
        }
    }

    @Test void transactionFailureRollsBackProposalVersionIndexJobAndStoredOriginal() {
        // Fails while the revision row is inserted, i.e. after the DRAFT version, its index job and the object exist.
        jdbc.execute("CREATE OR REPLACE FUNCTION fail_revision_for_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'failure injection'; END $$");
        jdbc.execute("CREATE TRIGGER trg_fail_revision_for_test BEFORE INSERT ON change_proposal_revision FOR EACH ROW WHEN (NEW.project_id="
                +project.projectId()+") EXECUTE FUNCTION fail_revision_for_test()");
        try {
            assertThatThrownBy(() -> create("rollback")).isInstanceOf(RuntimeException.class).hasMessageContaining("failure injection");
        } finally {
            jdbc.execute("DROP TRIGGER trg_fail_revision_for_test ON change_proposal_revision");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal WHERE project_id=?",Integer.class,project.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_http_command WHERE project_id=?",Integer.class,project.projectId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=? AND id<>?",Integer.class,document,version)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_processing p JOIN document_version v ON v.id=p.document_version_id WHERE v.document_id=?",
                Integer.class,document)).isZero();
        assertThat(storage.objects).isEmpty();
        assertThat(create("rollback").status()).isEqualTo("PROPOSED");
        assertThat(storage.objects).hasSize(1);
    }

    @Test void membershipRemovedWhileWaitingForProjectLockCannotSubmit() throws Exception {
        long account=fixtures().account(); long membership=fixtures().member(project.projectId(),account,ProjectRole.MEMBER);
        var waiting=CallerContext.human(account);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var submitted=new java.util.concurrent.atomic.AtomicReference<Future<Proposal>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE",Long.class,project.projectId());
                submitted.set(pool.submit(() -> commands.create(waiting,project.projectId(),input("대기 중 변경"),"removed")));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean blocked=false;
                while (System.nanoTime()<deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock'
                             AND query ILIKE '%SELECT id FROM project%FOR UPDATE%')
                            """,Boolean.class));
                    if (blocked) break;
                    try { Thread.sleep(20); } catch (InterruptedException e) {Thread.currentThread().interrupt(); throw new RuntimeException(e);}
                }
                assertThat(blocked).as("command waiting for project lock").isTrue();
                fixtures().removeMember(membership);
            });
            assertThatThrownBy(() -> submitted.get().get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(ApiException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal WHERE project_id=?",Integer.class,project.projectId())).isZero();
        }
    }

    @Test void foreignTaskAndValidationCannotBeAttachedEvenWithoutTaskId() {
        var other=fixtures().projectWithLeader();
        long task=jdbc.queryForObject("INSERT INTO task(project_id,key,title,status) VALUES(?,'T-1','외부','DRAFT') RETURNING id",Long.class,other.projectId());
        long plan=jdbc.queryForObject("""
                INSERT INTO task_plan_revision(task_id,revision_no,title,goal,input_hash,created_by_account_id)
                VALUES(?,1,'외부','외부','h',?) RETURNING id
                """,Long.class,task,other.leaderAccountId());
        long otherBaseline=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",Long.class,other.projectId(),other.leaderAccountId());
        long validation=jdbc.queryForObject("""
                INSERT INTO validation(task_id,plan_revision_id,baseline_id,status,trigger,input_snapshot_hash,rule_version)
                VALUES(?,?,?,'NOT_RUN','AGENT','h','1') RETURNING id
                """,Long.class,task,plan,otherBaseline);
        Input base=input("변경");
        error(() -> commands.create(author,project.projectId(),new Input(base.baseBaselineId(),base.title(),base.reason(),""+task,null,
                base.items(),base.documents(),null,null,null),"foreign-task"),"not_found");
        error(() -> commands.create(author,project.projectId(),new Input(base.baseBaselineId(),base.title(),base.reason(),null,""+validation,
                base.items(),base.documents(),null,null,null),"foreign-validation"),"not_found");
    }

    WithdrawRequest withdrawal(Proposal proposal) {
        return new WithdrawRequest(proposal.revision(), proposal.snapshotHash());
    }
    Input linkedInput(Proposal previous) {
        Input value=input("거둔 제안에서 새로 작성한 본문");
        return new Input(value.baseBaselineId(),value.title(),value.reason(),null,null,value.items(),value.documents(),
                null,null,previous.id());
    }

    @Test void withdrawalPreservesContentAndAuditAndReplaysWithoutPublishing() {
        Proposal original=create("withdraw-base"); long id=Long.parseLong(original.id());
        Proposal withdrawn=commands.withdraw(author,project.projectId(),id,withdrawal(original),"withdraw");
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.snapshotHash()).isEqualTo(original.snapshotHash());
        assertThat(withdrawn.revision()).isEqualTo(original.revision());
        assertThat(withdrawn.documents()).isEqualTo(original.documents());
        assertThat(withdrawn.withdrawnBy()).isEqualTo(author.accountId().toString());
        assertThat(withdrawn.withdrawnAt()).isNotBlank();
        assertThat(commands.withdraw(author,project.projectId(),id,withdrawal(original),"withdraw")).isEqualTo(withdrawn);
        error(() -> commands.withdraw(author,project.projectId(),id,new WithdrawRequest(2,original.snapshotHash()),"withdraw"),"idempotency_conflict");
        error(() -> commands.withdraw(author,project.projectId(),id,withdrawal(original),"again"),"invalid_transition");
        error(() -> commands.resubmit(author,project.projectId(),id,resubmission(original,"수정"),"resubmit"),"proposal_locked");
        assertThat(queries.detail(author,project.projectId(),id,1).proposal().withdrawnAt()).isEqualTo(withdrawn.withdrawnAt());
        assertThat(queries.revisions(author,project.projectId(),id,null,20).items()).singleElement().satisfies(r -> assertThat(r.decision()).isNull());
        assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?",Long.class,project.projectId())).isEqualTo(baseline);
        assertThatThrownBy(() -> jdbc.update("UPDATE change_proposal SET status='PROPOSED',withdrawn_by=NULL,withdrawn_at=NULL WHERE id=?",id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void withdrawalRejectsUnauthorizedStaleAndDecidedRequests() {
        Proposal p=create("protected"); long id=Long.parseLong(p.id());
        long member=fixtures().account(); fixtures().member(project.projectId(),member,ProjectRole.MEMBER);
        error(() -> commands.withdraw(CallerContext.human(member),project.projectId(),id,withdrawal(p),"other"),"forbidden");
        var foreign=fixtures().projectWithLeader();
        error(() -> commands.withdraw(CallerContext.human(foreign.leaderAccountId()),project.projectId(),id,withdrawal(p),"foreign"),"not_found");
        error(() -> commands.withdraw(author,project.projectId(),id,new WithdrawRequest(2,p.snapshotHash()),"stale"),"version_conflict");
        error(() -> commands.withdraw(author,project.projectId(),id,new WithdrawRequest(1,"sha256:"+"0".repeat(64)),"hash"),"version_conflict");
        error(() -> commands.withdraw(author,project.projectId(),id,new WithdrawRequest(0,p.snapshotHash()),"bad"),"validation_failed");
        reject(p);
        Proposal withdrawnAfterRejection=commands.withdraw(author,project.projectId(),id,withdrawal(p),"rejected");
        assertThat(withdrawnAfterRejection.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawnAfterRejection.revision()).isEqualTo(p.revision());
        assertThat(withdrawnAfterRejection.snapshotHash()).isEqualTo(p.snapshotHash());
        assertThat(queries.revisions(author,project.projectId(),id,null,20).items()).singleElement()
                .satisfies(r -> assertThat(r.decision()).isEqualTo("REJECTED"));
        error(() -> commands.resubmit(author,project.projectId(),id,resubmission(p,"거둔 뒤 보완"),"after-withdraw"),"proposal_locked");
        for(String status:List.of("APPROVED","PUBLISHED")) {
            Proposal decided=create(status);long decidedId=Long.parseLong(decided.id());
            jdbc.update("UPDATE change_proposal SET status=?,published_baseline_id=? WHERE id=?",
                    status,"PUBLISHED".equals(status) ? baseline : null,decidedId);
            error(() -> commands.withdraw(author,project.projectId(),decidedId,withdrawal(decided),status),"invalid_transition");
            assertThatThrownBy(() -> jdbc.update("UPDATE change_proposal SET status='WITHDRAWN',withdrawn_by=?,withdrawn_at=now() WHERE id=?",author.accountId(),decidedId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    @Test void withdrawnProposalCanBeLinkedOnlyByItsAuthorAndKeepsNewIdentity() {
        Proposal previous=create("previous");
        error(() -> commands.create(author,project.projectId(),linkedInput(previous),"premature"),"invalid_transition");
        commands.withdraw(author,project.projectId(),Long.parseLong(previous.id()),withdrawal(previous),"withdraw-previous");
        Proposal linked=commands.create(author,project.projectId(),linkedInput(previous),"linked");
        assertThat(linked.id()).isNotEqualTo(previous.id()); assertThat(linked.key()).isNotEqualTo(previous.key());
        assertThat(linked.previousProposalId()).isEqualTo(previous.id());
        assertThat(linked.revision()).isEqualTo(1);
        assertThat(commands.create(author,project.projectId(),linkedInput(previous),"linked")).isEqualTo(linked);
        long other=fixtures().account();fixtures().member(project.projectId(),other,ProjectRole.MEMBER);
        error(() -> commands.create(CallerContext.human(other),project.projectId(),linkedInput(previous),"other"),"forbidden");
        var foreign=fixtures().projectWithLeader();
        error(() -> commands.create(CallerContext.human(foreign.leaderAccountId()),foreign.projectId(),linkedInput(previous),"foreign"),"not_found");
        reject(linked);
        error(() -> commands.create(author,project.projectId(),linkedInput(linked),"rejected-link"),"invalid_transition");
        Proposal resubmitted=commands.resubmit(author,project.projectId(),Long.parseLong(linked.id()),resubmission(linked,"새 리비전"),"linked-resubmit");
        assertThat(resubmitted.previousProposalId()).isEqualTo(previous.id());
        assertThat(resubmitted.revision()).isEqualTo(2);
        commands.withdraw(author,project.projectId(),Long.parseLong(linked.id()),withdrawal(resubmitted),"withdraw-second");
        var historical=queries.detail(author,project.projectId(),Long.parseLong(linked.id()),1).proposal();
        assertThat(historical.status()).isEqualTo("REJECTED");assertThat(historical.withdrawnAt()).isNull();
        assertThatThrownBy(() -> jdbc.update("UPDATE change_proposal SET previous_proposal_id=NULL WHERE id=?",Long.parseLong(linked.id())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void groupFiltersBeforePaginationAndBindsCursorToGroup() {
        Proposal first=create("group-first"), second=create("group-second"), rejected=create("group-rejected"),withdrawn=create("group-withdrawn");
        reject(rejected);
        commands.withdraw(author,project.projectId(),Long.parseLong(withdrawn.id()),withdrawal(withdrawn),"group-withdraw");
        var page=queries.list(author,project.projectId(),null,"IN_PROGRESS",null,1);
        assertThat(page.items().getFirst().id()).isEqualTo(second.id());assertThat(page.hasMore()).isTrue();
        assertThat(queries.list(author,project.projectId(),null,"IN_PROGRESS",page.nextCursor(),1).items().getFirst().id()).isEqualTo(first.id());
        error(() -> queries.list(author,project.projectId(),null,"REJECTED",page.nextCursor(),1),"validation_failed");
        assertThat(queries.list(author,project.projectId(),"REJECTED","IN_PROGRESS",null,20).items()).isEmpty();
        assertThat(queries.list(author,project.projectId(),"WITHDRAWN",null,null,20).items()).singleElement().satisfies(p -> assertThat(p.id()).isEqualTo(withdrawn.id()));
        assertThat(queries.list(author,project.projectId(),null,"REJECTED",null,20).items()).singleElement().satisfies(p -> assertThat(p.id()).isEqualTo(rejected.id()));
        error(() -> queries.list(author,project.projectId(),null,"WITHDRAWN",null,20),"validation_failed");
        jdbc.update("UPDATE change_proposal SET status='APPROVED' WHERE id=?",Long.parseLong(first.id()));
        jdbc.update("UPDATE change_proposal SET status='PUBLISHED',published_baseline_id=? WHERE id=?",baseline,Long.parseLong(second.id()));
        assertThat(queries.list(author,project.projectId(),null,"IN_PROGRESS",null,20).items()).singleElement().satisfies(p -> assertThat(p.status()).isEqualTo("APPROVED"));
        assertThat(queries.list(author,project.projectId(),null,"PUBLISHED",null,20).items()).singleElement().satisfies(p -> assertThat(p.id()).isEqualTo(second.id()));
    }

    @Test void creationRequiresBaselineAndWithdrawalRestChecksOriginAndGroup() {
        var empty=fixtures().projectWithLeader();
        error(() -> commands.create(CallerContext.human(empty.leaderAccountId()),empty.projectId(),input("변경"),"no-baseline"),"baseline_missing");
        Proposal p=create("http-withdraw");
        var client=client().login(project.leaderEmail(),AccessFixtures.PASSWORD);
        String path="/api/projects/"+project.projectId()+"/change-proposals";
        String body=JSON.writeValueAsString(withdrawal(p));
        assertThat(client.post(path+"/"+p.id()+"/withdraw",body,"Idempotency-Key","rest-withdraw").statusCode()).isEqualTo(403);
        var result=client.post(path+"/"+p.id()+"/withdraw",body,"Origin",ORIGIN,"Idempotency-Key","rest-withdraw");
        assertThat(result.statusCode()).withFailMessage(result.body()).isEqualTo(200);
        assertThat(result.body()).startsWith("{\"id\":").doesNotContain("\"proposal\":{").contains("WITHDRAWN","withdrawn_at");
        assertThat(client.get(path+"?group=IN_PROGRESS").body()).contains("\"items\":[]");
        assertThat(client.get(path+"?group=UNKNOWN").statusCode()).isEqualTo(400);
    }

    @Test void concurrentWithdrawalReplaysOnceAndFailureRollsBack() throws Exception {
        Proposal p=create("withdraw-race");long id=Long.parseLong(p.id());
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            commands.withdraw(author,project.projectId(),id,withdrawal(p),"withdraw-rollback");throw new IllegalStateException("failure injection");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(queries.detail(author,project.projectId(),id,null).proposal().status()).isEqualTo("PROPOSED");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Proposal> action=() -> {start.await();return commands.withdraw(author,project.projectId(),id,withdrawal(p),"withdraw-race-key");};
            var a=pool.submit(action);var b=pool.submit(action);start.countDown();
            assertThat(a.get(15,TimeUnit.SECONDS)).isEqualTo(b.get(15,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM change_proposal_revision WHERE change_proposal_id=?",Integer.class,id)).isEqualTo(1);
    }

    @Test void withdrawalRechecksRevisionAfterConcurrentResubmission() throws Exception {
        Proposal p=create("withdraw-stale-race");long id=Long.parseLong(p.id());reject(p);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var waiting=new java.util.concurrent.atomic.AtomicReference<Future<Proposal>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE",Long.class,project.projectId());
                waiting.set(pool.submit(() -> commands.withdraw(author,project.projectId(),id,withdrawal(p),"old-withdraw")));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean blocked=false;
                while(System.nanoTime()<deadline) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock'
                            AND query ILIKE '%SELECT id FROM project%FOR UPDATE%')
                            """,Boolean.class));
                    if(blocked) break;
                    try {Thread.sleep(20);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new RuntimeException(e);}
                }
                assertThat(blocked).isTrue();
                // Uploading a text needs to start outside a DB transaction, so this winner submits a prepared draft.
                long draft=jdbc.queryForObject("""
                        INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,parent_version_id)
                        VALUES(?,(SELECT max(version_no)+1 FROM document_version WHERE document_id=?),'DRAFT','race.md','race-hash','첫째\n동시에 보완한 내용\n끝\n',?,?) RETURNING id
                        """,Long.class,document,document,author.accountId(),version);
                Input value=resubmission(p,"unused");
                commands.resubmit(author,project.projectId(),id,new Input(value.baseBaselineId(),value.title(),value.reason(),null,null,List.of(),
                        List.of(new DocumentChange(""+document,""+version,null,""+draft)),p.revision(),p.snapshotHash(),null),"new-revision");
            });
            assertThatThrownBy(() -> waiting.get().get(10,TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(ExecutionException.class,e -> {
                        assertThat(e.getCause()).isInstanceOf(ApiException.class);
                        assertThat(((ApiException)e.getCause()).getCode()).isEqualTo("version_conflict");
                    });
        }
        Proposal current=queries.detail(author,project.projectId(),id,null).proposal();
        assertThat(current.revision()).isEqualTo(2);assertThat(current.status()).isEqualTo("PROPOSED");
    }

    @Test void migrationPreservesPreexistingProposalAndDecisionWithoutInventingSchema() throws Exception {
        String schema="proposal_migration_"+UUID.randomUUID().toString().replace("-","");
        try (var connection=Objects.requireNonNull(jdbc.getDataSource()).getConnection(); var sql=connection.createStatement()) {
            sql.execute("CREATE SCHEMA "+schema);
            try {
                sql.execute("SET search_path TO "+schema+", public");
                for (String file : List.of("V2__init_schema.sql","V3__document_processing.sql","V4__member_invitation.sql",
                        "V5__context_access_log_account.sql","V6__design_command.sql")) {
                    sql.execute(new org.springframework.core.io.ClassPathResource("db/migration/"+file).getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                }
                sql.execute("""
                        INSERT INTO account(email,display_name) VALUES('legacy@example.com','기존');
                        INSERT INTO project(name,owner_account_id) VALUES('기존',1);
                        INSERT INTO baseline(project_id,version_no,published_by) VALUES(1,1,1);
                        INSERT INTO change_proposal(project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,reason)
                        VALUES(1,'CP-OLD','REJECTED',1,'HUMAN',1,'기존 사유');
                        INSERT INTO change_proposal_item(change_proposal_id,op,new_kind,proposed_content)
                        VALUES(1,'CREATE','REQUIREMENT','{"description":"기존 제안"}');
                        INSERT INTO approval(change_proposal_id,decided_by,decision,reason,base_baseline_id)
                        VALUES(1,1,'REJECTED','기존 반려',1);
                        """);
                sql.execute(new org.springframework.core.io.ClassPathResource("db/migration/V10__change_proposal_revisions.sql")
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                try (var rows=sql.executeQuery("SELECT r.snapshot::text,a.reason FROM change_proposal_revision r JOIN approval a ON a.change_proposal_id=r.change_proposal_id AND a.proposal_revision_no=r.revision_no")) {
                    assertThat(rows.next()).isTrue();
                    Snapshot snapshot=JSON.readValue(rows.getString(1),Snapshot.class);
                    assertThat(snapshot.legacy()).isTrue();
                    assertThat(snapshot.input().items().getFirst().schemaVersion()).isNull();
                    assertThat(rows.getString(2)).isEqualTo("기존 반려");
                }
            } finally {
                sql.execute("SET search_path TO public");
                sql.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
}
