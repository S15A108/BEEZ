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

    @Test void expiredRunningLeaseIsClosedAsFailedSoAReasonCanOverrideAndPreviewStaysReadOnly() {
        runs.retry(member,project,proposalId,null);
        api.jdbc.sql("UPDATE change_impact_run SET lease_until=clock_timestamp()-interval '1 second' WHERE change_proposal_id=:p").param("p",proposalId).update();
        // 읽기 전용 미리 보기는 만료 정리를 쓰지 않으므로 예외 없이 RUNNING 으로 보인다
        assertThat(publications.preview(leader,project,proposalId).unreviewed().aiAnalysis().status()).isEqualTo("RUNNING");
        blocked("impact_not_confirmed",null); // 승인은 쓰기 트랜잭션이라 만료 RUNNING 을 FAILED 로 닫는다
        assertThat(approve("expired","분석이 멈춰서 직접 확인").proposal().status()).isEqualTo("PUBLISHED");
        assertThat(api.jdbc.sql("SELECT error_code FROM change_impact_run WHERE change_proposal_id=:p").param("p",proposalId).query(String.class).single())
                .isEqualTo("analysis_interrupted");
    }
}
