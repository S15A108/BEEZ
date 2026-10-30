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
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** CHG-A08 review start, CHG-A10 rejection and CHG-A11 approval history. */
@Import(ProposalIntegrationTest.Support.class)
class ProposalDecisionIntegrationTest extends IntegrationTestSupport {
    @Autowired ProposalIntegrationTest.MemoryStorage storage;
    @Autowired ProposalCommandService commands;
    @Autowired ProposalQueryService queries;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    AccessFixtures.ProjectWithLeader project;
    CallerContext leader;
    long baseline, document, version;
    static final String ORIGIN = "http://localhost:5173";

    @BeforeEach void setup() {
        storage.objects.clear();
        project=fixtures().projectWithLeader(); leader=CallerContext.human(project.leaderAccountId());
        baseline=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class, project.projectId(), leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        document=jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id",
                Long.class, project.projectId(), leader.accountId());
        version=jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','fixture.md','hash-before','첫째\n이전\n끝\n',?,?,now()) RETURNING id
                """, Long.class, document, leader.accountId(), leader.accountId());
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project.projectId(), document, version);
    }

    /** Proposed by a plain member so the leader decides on someone else's proposal. */
    long proposer;
    Proposal create(String key) {
        if (proposer == 0) { proposer=fixtures().account(); fixtures().member(project.projectId(),proposer,ProjectRole.MEMBER); }
        var input=new Input(""+baseline,"문서 보완","반영 이유",null,null,List.of(),
                List.of(new DocumentChange(""+document,""+version,"첫째\n수정"+key+"\n끝\n",null)),null,null,null);
        return commands.create(CallerContext.human(proposer),project.projectId(),input,key);
    }
    WithdrawRequest review(Proposal p) { return new WithdrawRequest(p.revision(),p.snapshotHash()); }
    RejectRequest rejection(Proposal p, String reason) { return new RejectRequest(reason,p.revision(),p.snapshotHash()); }
    long id(Proposal p) { return Long.parseLong(p.id()); }
    void error(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo(code));
    }
    int approvals(Proposal p) {
        return jdbc.queryForObject("SELECT count(*) FROM approval WHERE change_proposal_id=?",Integer.class,id(p));
    }

    @Test void reviewStartMovesToInReviewAndIsIdempotent() {
        Proposal p=create("review");
        Proposal reviewing=commands.startReview(leader,project.projectId(),id(p),review(p),"r1");
        assertThat(reviewing.status()).isEqualTo("IN_REVIEW");
        assertThat(reviewing.revision()).isEqualTo(p.revision());
        assertThat(commands.startReview(leader,project.projectId(),id(p),review(p),"r1")).isEqualTo(reviewing);
        assertThat(commands.startReview(leader,project.projectId(),id(p),review(p),"r2").status()).isEqualTo("IN_REVIEW");
        error(() -> commands.startReview(leader,project.projectId(),id(p),new WithdrawRequest(2,p.snapshotHash()),"r3"),"version_conflict");
        error(() -> commands.startReview(leader,project.projectId(),id(p),new WithdrawRequest(1,"sha256:"+"0".repeat(64)),"r4"),"version_conflict");
        error(() -> commands.startReview(leader,project.projectId(),id(p),new WithdrawRequest(null,null),"r5"),"validation_failed");
    }

    @Test void onlyHumanLeaderDecidesAndOutsidersSeeNothing() {
        Proposal p=create("auth");
        long member=fixtures().account(); fixtures().member(project.projectId(),member,ProjectRole.MEMBER);
        error(() -> commands.startReview(CallerContext.human(member),project.projectId(),id(p),review(p),"m"),"forbidden");
        error(() -> commands.reject(CallerContext.human(member),project.projectId(),id(p),rejection(p,"사유"),"m"),"forbidden");
        long leaderAgent=fixtures().agentToken(project.projectId(),leader.accountId());
        error(() -> commands.reject(CallerContext.agent(leader.accountId(),leaderAgent),project.projectId(),id(p),rejection(p,"사유"),"a"),"forbidden");
        var foreign=fixtures().projectWithLeader();
        error(() -> commands.reject(CallerContext.human(foreign.leaderAccountId()),project.projectId(),id(p),rejection(p,"사유"),"f"),"not_found");
        var other=fixtures().projectWithLeader();
        error(() -> commands.reject(CallerContext.human(other.leaderAccountId()),other.projectId(),id(p),rejection(p,"사유"),"x"),"not_found");
        assertThat(approvals(p)).isZero();
        assertThat(queries.detail(leader,project.projectId(),id(p),null).proposal().status()).isEqualTo("PROPOSED");
    }

    @Test void rejectionStoresDecisionAndStatusAndAllowsResubmission() {
        Proposal p=create("reject");
        Proposal rejected=commands.reject(leader,project.projectId(),id(p),rejection(p,"  근거를 보완해 주세요.  "),"rej");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.rejectionReason()).isEqualTo("근거를 보완해 주세요.");
        assertThat(rejected.revision()).isEqualTo(p.revision());
        assertThat(rejected.snapshotHash()).isEqualTo(p.snapshotHash());
        assertThat(commands.reject(leader,project.projectId(),id(p),rejection(p,"  근거를 보완해 주세요.  "),"rej")).isEqualTo(rejected);
        assertThat(approvals(p)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT base_baseline_id FROM approval WHERE change_proposal_id=?",Long.class,id(p))).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT decided_by FROM approval WHERE change_proposal_id=?",Long.class,id(p))).isEqualTo(leader.accountId());
        var history=queries.revisions(leader,project.projectId(),id(p),null,20).items();
        assertThat(history.getFirst().decision()).isEqualTo("REJECTED");
        assertThat(history.getFirst().decisionReason()).isEqualTo("근거를 보완해 주세요.");
        error(() -> commands.reject(leader,project.projectId(),id(p),rejection(p,"다시"),"rej-again"),"invalid_transition");
        error(() -> commands.startReview(leader,project.projectId(),id(p),review(p),"after"),"invalid_transition");
        assertThat(approvals(p)).isEqualTo(1);
        // CHG-A04 needs the stored reason; resubmission keeps the first decision.
        var next=new Input(""+baseline,"문서 보완","반영 이유",null,null,List.of(),
                List.of(new DocumentChange(""+document,""+version,"두번째 보완",null)),rejected.revision(),rejected.snapshotHash(),null);
        Proposal second=commands.resubmit(CallerContext.human(proposer),project.projectId(),id(p),next,"resubmit");
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("PROPOSED");
        error(() -> commands.reject(leader,project.projectId(),id(p),rejection(p,"옛 화면"),"stale"),"version_conflict");
        assertThat(queries.revisions(leader,project.projectId(),id(p),null,20).items()).hasSize(2);
        assertThat(queries.revisions(leader,project.projectId(),id(p),null,20).items().get(1).decisionReason()).isEqualTo("근거를 보완해 주세요.");
    }

    @Test void rejectionWorksAfterReviewAndWhenBaselineMoved() {
        Proposal p=create("moved");
        commands.startReview(leader,project.projectId(),id(p),review(p),"start");
        long next=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",
                Long.class,project.projectId(),leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",next,project.projectId());
        assertThat(queries.detail(leader,project.projectId(),id(p),null).proposal().requiresRefresh()).isTrue();
        Proposal rejected=commands.reject(leader,project.projectId(),id(p),rejection(p,"기준이 바뀌어 반려"),"moved");
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT base_baseline_id FROM approval WHERE change_proposal_id=?",Long.class,id(p))).isEqualTo(baseline);
    }

    @Test void rejectionValidatesReasonAndLeavesNothingOnFailure() {
        Proposal p=create("invalid");
        error(() -> commands.reject(leader,project.projectId(),id(p),rejection(p,"   "),"blank"),"validation_failed");
        error(() -> commands.reject(leader,project.projectId(),id(p),rejection(p,null),"null"),"validation_failed");
        error(() -> commands.reject(leader,project.projectId(),id(p),rejection(p,"가".repeat(10001)),"long"),"validation_failed");
        error(() -> commands.reject(leader,project.projectId(),id(p),new RejectRequest("사유",0,p.snapshotHash()),"rev"),"validation_failed");
        assertThat(approvals(p)).isZero();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            commands.reject(leader,project.projectId(),id(p),rejection(p,"롤백"),"rollback");
            throw new IllegalStateException("failure injection");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(approvals(p)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?",String.class,id(p))).isEqualTo("PROPOSED");
    }

    @Test void withdrawnAndPublishedProposalsCannotBeDecided() {
        Proposal withdrawn=create("withdrawn");
        commands.withdraw(CallerContext.human(proposer),project.projectId(),id(withdrawn),new WithdrawRequest(withdrawn.revision(),withdrawn.snapshotHash()),"w");
        error(() -> commands.startReview(leader,project.projectId(),id(withdrawn),review(withdrawn),"w1"),"invalid_transition");
        error(() -> commands.reject(leader,project.projectId(),id(withdrawn),rejection(withdrawn,"사유"),"w2"),"invalid_transition");
        Proposal published=create("published");
        publish(published);
        error(() -> commands.reject(leader,project.projectId(),id(published),rejection(published,"사유"),"p1"),"invalid_transition");
        error(() -> commands.startReview(leader,project.projectId(),id(published),review(published),"p2"),"invalid_transition");
    }

    @Test void concurrentRejectionsStoreOneDecision() throws Exception {
        Proposal p=create("race");
        var start=new CountDownLatch(1);
        try (var pool=Executors.newFixedThreadPool(2)) {
            Callable<Object> a=() -> { start.await(); return commands.reject(leader,project.projectId(),id(p),rejection(p,"동시 사유"),"race-a"); };
            Callable<Object> b=() -> { start.await(); return commands.reject(leader,project.projectId(),id(p),rejection(p,"동시 사유"),"race-b"); };
            var first=pool.submit(a); var second=pool.submit(b); start.countDown();
            int ok=0, conflict=0;
            for (var future : List.of(first,second)) {
                try { future.get(10,TimeUnit.SECONDS); ok++; }
                catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ApiException.class,x -> assertThat(x.getCode()).isEqualTo("invalid_transition"));
                    conflict++;
                }
            }
            assertThat(ok).isEqualTo(1); assertThat(conflict).isEqualTo(1);
        }
        assertThat(approvals(p)).isEqualTo(1);
    }

    @Test void leaderDelegatedWhileWaitingForProjectLockIsForbidden() throws Exception {
        Proposal p=create("delegated");
        long successor=fixtures().account(); long successorMember=fixtures().member(project.projectId(),successor,ProjectRole.MEMBER);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var waiting=new java.util.concurrent.atomic.AtomicReference<Future<Proposal>>();
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM project WHERE id=? FOR UPDATE",Long.class,project.projectId());
                waiting.set(pool.submit(() -> commands.reject(leader,project.projectId(),id(p),rejection(p,"위임 전 요청"),"delegated")));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                boolean blocked=false;
                while (System.nanoTime()<deadline && !blocked) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()");
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock'
                             AND query ILIKE '%FROM project WHERE id%FOR UPDATE%')
                            """,Boolean.class));
                    if (!blocked) try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                }
                assertThat(blocked).as("reject waiting for project lock").isTrue();
                jdbc.update("UPDATE project_member SET role='MEMBER' WHERE id=?",project.leaderMemberId());
                jdbc.update("UPDATE project_member SET role='LEADER' WHERE id=?",successorMember);
            });
            assertThatThrownBy(() -> waiting.get().get(10,TimeUnit.SECONDS)).cause().isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getCode()).isEqualTo("forbidden"));
        }
        assertThat(approvals(p)).isZero();
    }

    /** Stands in for CHG-A09, which lands in a later PR. */
    void publish(Proposal p) {
        long published=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id",
                Long.class,project.projectId(),leader.accountId());
        jdbc.update("""
                INSERT INTO approval(change_proposal_id,proposal_revision_no,decided_by,decision,base_baseline_id,result_baseline_id)
                VALUES(?,?,?,'APPROVED',?,?)
                """,id(p),p.revision(),leader.accountId(),baseline,published);
        jdbc.update("UPDATE change_proposal SET status='PUBLISHED',published_baseline_id=? WHERE id=?",published,id(p));
    }

    @Test void approvalHistoryReturnsOnlyPublishedApproval() {
        Proposal pending=create("pending"), rejected=create("rejected"), published=create("history");
        commands.reject(leader,project.projectId(),id(rejected),rejection(rejected,"사유"),"h-rej");
        error(() -> queries.approval(leader,project.projectId(),id(pending)),"not_found");
        error(() -> queries.approval(leader,project.projectId(),id(rejected)),"not_found");
        publish(published);
        ApprovalRecord record=queries.approval(CallerContext.human(proposer),project.projectId(),id(published));
        assertThat(record.proposalId()).isEqualTo(published.id());
        assertThat(record.approvedBy()).isEqualTo(Long.toString(leader.accountId()));
        assertThat(record.baselineId()).isEqualTo(jdbc.queryForObject("SELECT published_baseline_id FROM change_proposal WHERE id=?",String.class,id(published)));
        assertThat(record.approvedAt()).isNotBlank();
        var foreign=fixtures().projectWithLeader();
        error(() -> queries.approval(CallerContext.human(foreign.leaderAccountId()),project.projectId(),id(published)),"not_found");
        error(() -> queries.approval(CallerContext.human(foreign.leaderAccountId()),foreign.projectId(),id(published)),"not_found");
        error(() -> queries.approval(leader,project.projectId(),999_999L),"not_found");
    }

    @Test void restChecksOriginIdempotencyKeyAndShape() {
        Proposal p=create("http");
        var client=client().login(project.leaderEmail(),AccessFixtures.PASSWORD);
        String path="/api/projects/"+project.projectId()+"/change-proposals/"+p.id();
        assertThat(client.post(path+"/review",JSON.writeValueAsString(review(p)),"Idempotency-Key","http-review").statusCode()).isEqualTo(403);
        var reviewed=client.post(path+"/review",JSON.writeValueAsString(review(p)),"Origin",ORIGIN,"Idempotency-Key","http-review");
        assertThat(reviewed.statusCode()).withFailMessage(reviewed.body()).isEqualTo(200);
        assertThat(reviewed.body()).startsWith("{\"id\":").contains("IN_REVIEW");
        assertThat(client.post(path+"/reject",JSON.writeValueAsString(rejection(p,"사유")),"Origin",ORIGIN).statusCode()).isEqualTo(400);
        var rejected=client.post(path+"/reject",JSON.writeValueAsString(rejection(p,"HTTP 반려")),"Origin",ORIGIN,"Idempotency-Key","http-reject");
        assertThat(rejected.statusCode()).withFailMessage(rejected.body()).isEqualTo(200);
        assertThat(rejected.body()).contains("REJECTED","HTTP 반려","rejection_reason");
        var again=client.post(path+"/reject",JSON.writeValueAsString(rejection(p,"다른 사유")),"Origin",ORIGIN,"Idempotency-Key","http-reject-2");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("invalid_transition");
        assertThat(client.get(path+"/approval").statusCode()).isEqualTo(404);
    }
}
