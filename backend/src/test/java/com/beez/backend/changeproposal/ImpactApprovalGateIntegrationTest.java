package com.beez.backend.changeproposal;

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
import com.beez.backend.impactanalysis.ImpactRunExecutor;
import com.beez.backend.impactanalysis.ImpactRunService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

/** S15P31A108-241: the approval gate reads the real analysis state produced by the 197 run service. */
class ImpactApprovalGateIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ImpactRunService runs;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @MockitoBean AiClient ai;
    @MockitoBean ImpactRunExecutor executor;
    TaskApiSupport api;
    long project,proposalId;
    Proposal proposal;
    CallerContext member,leader;
    final List<Runnable> queued=new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        api=new TaskApiSupport(this::client,dataSource); api.resetAndLoadFixture(); queued.clear();
        project=api.projectId(); member=CallerContext.human(api.accountId("member@test")); leader=CallerContext.human(api.accountId("leader@test"));
        long baseline=api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p",project).query(Long.class).single();
        proposal=proposals.create(member,project,new Input(""+baseline,"retire","retire API",null,null,
                List.of(new Item("RETIRE",""+api.designItemId("API-01"),""+api.currentVersionId("API-01"),null,null,null)),
                List.of(),null,null,null),"create");
        proposalId=Long.parseLong(proposal.id());
        when(executor.submit(any())).thenAnswer(call -> { queued.add(call.getArgument(0)); return true; });
        doReturn(Map.of("result",Map.of("candidates",List.of(),"evidence_status","SUFFICIENT"),"origin","MODEL",
                "usage",List.of(Map.of("request_key","llm-call","feature","CHANGE_IMPACT","failed",false)))).when(ai).changeImpact(any());
    }
    void analyze() { runs.retry(member,project,proposalId,null); queued.removeFirst().run(); }
    com.beez.backend.changeproposal.dto.PublicationDtos.Publication approve(String key,String reason) {
        return publications.approve(leader,project,proposalId,
                new ApproveRequest(proposal.baseBaselineId(),proposal.revision(),proposal.snapshotHash(),reason),key);
    }
    void blocked(String code,String reason) {
        assertThatThrownBy(() -> approve("k-"+UUID.randomUUID(),reason)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo(code));
        assertThat(api.jdbc.sql("SELECT status FROM change_proposal WHERE id=:p").param("p",proposalId).query(String.class).single()).isEqualTo("PROPOSED");
        assertThat(api.jdbc.sql("SELECT count(*) FROM approval WHERE change_proposal_id=:p").param("p",proposalId).query(Integer.class).single()).isZero();
    }
    String storedReason() {
        return api.jdbc.sql("SELECT reason FROM approval WHERE change_proposal_id=:p").param("p",proposalId).query(String.class).optional().orElse(null);
    }

    @Test void neverAnalyzedProposalNeedsAReason() {
        blocked("impact_not_confirmed",null);
        assertThat(publications.preview(leader,project,proposalId).unreviewed().requiresOverrideReason()).isTrue();
        assertThat(approve("with-reason","분석 없이 직접 확인").proposal().status()).isEqualTo("PUBLISHED");
        assertThat(storedReason()).isEqualTo("분석 없이 직접 확인");
    }

    @Test void completedCurrentAnalysisPassesEvenWithZeroCandidates() {
        analyze();
        var pre=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(pre.requiresOverrideReason()).isFalse(); assertThat(pre.aiCandidates()).isZero();
        assertThat(pre.aiAnalysis().status()).isEqualTo("COMPLETED"); assertThat(pre.aiAnalysis().isCurrent()).isTrue();
        assertThat(approve("trusted",null).proposal().status()).isEqualTo("PUBLISHED");
        assertThat(storedReason()).isNull();
    }

    @Test void failedAnalysisIsNotZeroImpact() {
        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("timeout"));
        analyze();
        blocked("impact_not_confirmed",null);
        var pre=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(pre.aiAnalysis().status()).isEqualTo("FAILED"); assertThat(pre.aiCandidates()).isNull();
        assertThat(approve("failed-with-reason","AI 장애").proposal().status()).isEqualTo("PUBLISHED");
    }

    @Test void insufficientEvidenceNeedsAReason() {
        doReturn(Map.of("result",Map.of("candidates",List.of(),"evidence_status","INSUFFICIENT"),"origin","MODEL",
                "usage",List.of(Map.of("request_key","llm-call","feature","CHANGE_IMPACT","failed",false)))).when(ai).changeImpact(any());
        analyze();
        blocked("impact_not_confirmed",null);
        assertThat(approve("insufficient","근거 부족 확인").proposal().status()).isEqualTo("PUBLISHED");
    }

    @Test void resultForChangedInputIsNotTrusted() {
        analyze();
        api.jdbc.sql("UPDATE change_impact_run SET input_fingerprint='changed' WHERE change_proposal_id=:p").param("p",proposalId).update();
        blocked("impact_not_confirmed",null);
        assertThat(publications.preview(leader,project,proposalId).unreviewed().aiAnalysis().isCurrent()).isFalse();
    }

    @Test void runningAnalysisBlocksEvenWithAReason() {
        runs.retry(member,project,proposalId,null);
        blocked("analysis_running","그래도 승인");
        var pre=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(pre.aiAnalysis().status()).isEqualTo("RUNNING"); assertThat(pre.requiresOverrideReason()).isFalse();
    }

    @Test void reanalysisInProgressBlocksEvenWhenThePreviousResultIsCompletedAndCurrent() {
        analyze();
        runs.retry(member,project,proposalId,null); // 재분석 접수: 이전 완료 결과가 그대로 보이고 새 실행은 reanalysis 로만 나온다
        assertThat(runs.get(member,project,proposalId).analysisStatus()).isEqualTo("COMPLETED");
        blocked("analysis_running",null);
        blocked("analysis_running","그래도 승인");
        var pre=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(pre.aiAnalysis().status()).isEqualTo("COMPLETED"); assertThat(pre.aiAnalysis().reanalysisInProgress()).isTrue();
        assertThat(pre.requiresOverrideReason()).isFalse(); assertThat(pre.aiCandidates()).isNull();
        queued.removeFirst().run(); // 재분석이 끝나면 다시 승인할 수 있다
        assertThat(approve("after-reanalysis",null).proposal().status()).isEqualTo("PUBLISHED");
    }

    @Test void reanalysisInProgressCannotBeOverriddenWithAReasonAfterAFailedRun() {
        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("timeout"));
        analyze();
        assertThat(publications.preview(leader,project,proposalId).unreviewed().requiresOverrideReason()).isTrue();
        runs.retry(member,project,proposalId,null);
        blocked("analysis_running","실패했으니 사유로 승인");
        var pre=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(pre.aiAnalysis().status()).isEqualTo("FAILED"); assertThat(pre.aiAnalysis().reanalysisInProgress()).isTrue();
        assertThat(pre.requiresOverrideReason()).isFalse();
    }

    @Test void expiredRunningReadsAsFailedEverywhereSoRolledBackApprovalsStayConsistent() {
        runs.retry(member,project,proposalId,null);
        api.jdbc.sql("UPDATE change_impact_run SET lease_until=clock_timestamp()-interval '1 second' WHERE change_proposal_id=:p").param("p",proposalId).update();
        // 읽기 전용 미리 보기도 쓰기 없이 만료를 FAILED 로 판정한다 — 승인 판정과 같다
        var pre=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(pre.aiAnalysis().status()).isEqualTo("FAILED"); assertThat(pre.aiAnalysis().errorCode()).isEqualTo("analysis_interrupted");
        assertThat(pre.aiAnalysis().reanalysisInProgress()).isFalse(); assertThat(pre.requiresOverrideReason()).isTrue();
        blocked("impact_not_confirmed",null); // 승인 트랜잭션이 롤백돼 만료 정리도 되돌아간다
        assertThat(api.jdbc.sql("SELECT status FROM change_impact_run WHERE change_proposal_id=:p").param("p",proposalId).query(String.class).single()).isEqualTo("RUNNING");
        var again=publications.preview(leader,project,proposalId).unreviewed(); // 롤백 뒤에도 미리 보기가 같다
        assertThat(again.aiAnalysis().status()).isEqualTo("FAILED"); assertThat(again.requiresOverrideReason()).isTrue();
        assertThat(approve("expired","분석이 멈춰서 직접 확인").proposal().status()).isEqualTo("PUBLISHED");
        assertThat(api.jdbc.sql("SELECT error_code FROM change_impact_run WHERE change_proposal_id=:p").param("p",proposalId).query(String.class).single())
                .isEqualTo("analysis_interrupted");
    }

    @Test void expiredReanalysisFallsBackToTheFailureNotTheOlderCompletedResult() {
        analyze();
        runs.retry(member,project,proposalId,null);
        // 현실에서는 재분석의 lease 가 이전 완료 시각보다 늦다 — 시험에서 인위로 당긴 lease 와 순서가 뒤집히지 않게 완료 시각을 과거로 둔다
        api.jdbc.sql("UPDATE change_impact_run SET requested_at=requested_at-interval '2 hours',finished_at=finished_at-interval '2 hours',started_at=started_at-interval '2 hours' WHERE status='COMPLETED' AND change_proposal_id=:p").param("p",proposalId).update();
        api.jdbc.sql("UPDATE change_impact_run SET lease_until=clock_timestamp()-interval '1 second' WHERE status='RUNNING' AND change_proposal_id=:p").param("p",proposalId).update();
        var before=publications.preview(leader,project,proposalId).unreviewed();
        runs.get(member,project,proposalId); // 만료 정리를 실제로 저장한 뒤에도 판정이 같아야 한다
        var after=publications.preview(leader,project,proposalId).unreviewed();
        assertThat(before.aiAnalysis()).isEqualTo(after.aiAnalysis());
        assertThat(after.aiAnalysis().status()).isEqualTo("FAILED"); assertThat(after.requiresOverrideReason()).isTrue();
    }

    @Test void approvalRequestsStoredBeforeTheReasonFieldStillReplayWithTheSameKeyAndBody() {
        analyze();
        var first=approve("old-key",null);
        var old=Map.of("expected_base_baseline_id",proposal.baseBaselineId(),"expected_revision",proposal.revision(),
                "expected_snapshot_hash",proposal.snapshotHash());
        assertThat(com.beez.backend.designitem.support.DesignJson.hash(new ApproveRequest(proposal.baseBaselineId(),proposal.revision(),
                proposal.snapshotHash(),null))).isEqualTo(com.beez.backend.designitem.support.DesignJson.hash(old));
        // 사유 필드가 없던 시절 저장된 지문으로 되돌린 뒤 같은 키·본문으로 재시도한다
        assertThat(api.jdbc.sql("UPDATE design_http_command SET fingerprint=:f WHERE request_key='old-key'")
                .param("f",com.beez.backend.designitem.support.DesignJson.hash(old)).update()).isEqualTo(1);
        assertThat(approve("old-key",null)).isEqualTo(first);
    }
}
