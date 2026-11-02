package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.*;
import com.beez.backend.task.TaskApiSupport;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

class ImpactRunIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ImpactRunService runs;
    @Autowired ProposalCommandService proposals;
    @MockitoBean AiClient ai;
    @MockitoBean ImpactRunExecutor executor;
    TaskApiSupport api;
    long project,member,baseline,proposal;
    CallerContext caller;
    final List<Runnable> queued=new CopyOnWriteArrayList<>();

    @BeforeEach void setup() {
        api=new TaskApiSupport(this::client,dataSource); api.resetAndLoadFixture(); queued.clear();
        project=api.projectId(); member=api.accountId("member@test"); caller=CallerContext.human(member);
        baseline=api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p",project).query(Long.class).single();
        var p=proposals.create(caller,project,new Input(""+baseline,"retire","retire API",null,null,
                List.of(new Item("RETIRE",""+api.designItemId("API-01"),""+api.currentVersionId("API-01"),null,null,null)),
                List.of(),null,null,null),"create");
        proposal=Long.parseLong(p.id());
        when(executor.submit(any())).thenAnswer(call -> { queued.add(call.getArgument(0)); return true; });
        when(ai.changeImpact(any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return envelope("SUFFICIENT");
        });
    }
    Map<String,Object> envelope(String evidence) {
        return Map.of("result",Map.of("candidates",List.of(),"evidence_status",evidence),"origin","MODEL",
                "usage",List.of(Map.of("request_key","llm-call","feature","CHANGE_IMPACT","failed",false)));
    }
    void work() { queued.removeFirst().run(); }
    void error(Runnable work,String code) { assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getCode()).isEqualTo(code)); }

    @Test void acceptsReplaysCompletesZeroCandidatesAndCreatesUniqueUsageKeys() {
        assertThat(runs.get(caller,project,proposal).errorCode()).isEqualTo("analysis_not_started");
        var accepted=runs.retry(caller,project,proposal,"key");
        assertThat(accepted.analysisStatus()).isEqualTo("RUNNING");
        assertThat(runs.retry(caller,project,proposal,"key")).isEqualTo(accepted);
        error(() -> runs.retry(caller,project,proposal,"other"),"analysis_running");
        assertThat(queued).hasSize(1); work();
        var result=runs.get(caller,project,proposal);
        assertThat(result.analysisStatus()).isEqualTo("COMPLETED"); assertThat(result.candidates()).isEmpty(); assertThat(result.isCurrent()).isTrue();
        runs.retry(caller,project,proposal,null); work();
        assertThat(api.jdbc.sql("SELECT count(DISTINCT request_key) FROM change_impact_run WHERE change_proposal_id=:p")
                .param("p",proposal).query(Integer.class).single()).isEqualTo(2);
        assertThat(api.jdbc.sql("SELECT usage_status FROM change_impact_run WHERE change_proposal_id=:p ORDER BY id DESC LIMIT 1")
                .param("p",proposal).query(String.class).single()).isEqualTo("REPORTED");
    }

    @Test void failedAiPreservesExplicitAndInsufficientEvidenceIsDistinct() {
        var task=api.post("/api/projects/"+project+"/tasks",api.planJson("consumer",api.readsRef("API-01"),"[]"),member);
        assertThat(task.statusCode()).isEqualTo(201);
        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("timeout"));
        runs.retry(caller,project,proposal,null); work();
        var failed=runs.get(caller,project,proposal);
        assertThat(failed.analysisStatus()).isEqualTo("FAILED"); assertThat(failed.explicit()).hasSize(1);
        doReturn(envelope("INSUFFICIENT")).when(ai).changeImpact(any());
        runs.retry(caller,project,proposal,null); work();
        assertThat(runs.get(caller,project,proposal).analysisStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }

    @Test void changesDuringModelCallBecomeStaleAndExpiredWorkersCannotOverwrite() {
        when(ai.changeImpact(any())).thenAnswer(call -> {
            api.jdbc.sql("UPDATE change_proposal SET lock_version=lock_version+1 WHERE id=:id").param("id",proposal).update();
            return envelope("SUFFICIENT");
        });
        runs.retry(caller,project,proposal,null); work();
        var stale=runs.get(caller,project,proposal);
        assertThat(stale.analysisStatus()).isEqualTo("STALE"); assertThat(stale.isCurrent()).isFalse(); assertThat(stale.candidates()).isEmpty();
        when(ai.changeImpact(any())).thenReturn(envelope("SUFFICIENT"));
        runs.retry(caller,project,proposal,null);
        api.jdbc.sql("UPDATE change_impact_run SET lease_until=clock_timestamp()-interval '1 second' WHERE status='RUNNING'").update();
        runs.retry(caller,project,proposal,null);
        work(); // old queued worker must not run or overwrite anything
        assertThat(runs.get(caller,project,proposal).errorCode()).isEqualTo("analysis_interrupted");
        work(); assertThat(runs.get(caller,project,proposal).analysisStatus()).isEqualTo("COMPLETED");
    }

    @Test void capacityFailureIsDurableAndMembershipOriginAndHumanChecksApply() {
        when(executor.submit(any())).thenReturn(false);
        runs.retry(caller,project,proposal,null);
        assertThat(runs.get(caller,project,proposal).errorCode()).isEqualTo("analysis_capacity");
        error(() -> runs.retry(CallerContext.human(api.accountId("outsider@test")),project,proposal,null),"not_found");
        long token=api.jdbc.sql("SELECT id FROM agent_token WHERE account_id=:a").param("a",member).query(Long.class).single();
        assertThatThrownBy(() -> runs.retry(CallerContext.agent(member,token),project,proposal,null))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus().value()).isEqualTo(403));
        var client=client().login("member@test",AccessFixtures.PASSWORD);
        String path="/api/projects/"+project+"/change-proposals/"+proposal+"/impact";
        assertThat(client.post(path+"/retry",null).statusCode()).isEqualTo(403);
        var response=client.post(path+"/retry",null,"Origin","http://localhost:5173");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(202);
        assertThat(response.body()).contains("\"analysis_status\":\"RUNNING\"","\"revision\":1","\"is_current\":true");
        assertThat(client.get(path).statusCode()).isEqualTo(200);
    }

    @Test void baselineChangesDuringAiCallCanPersistStaleWithoutRollbackOnlyFailure() {
        when(ai.changeImpact(any())).thenAnswer(call -> {
            long next=api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,2,:a) RETURNING id")
                    .param("p",project).param("a",api.accountId("leader@test")).query(Long.class).single();
            api.jdbc.sql("UPDATE project SET current_baseline_id=:b WHERE id=:p").param("b",next).param("p",project).update();
            return envelope("SUFFICIENT");
        });
        runs.retry(caller,project,proposal,null); work();
        var result=runs.get(caller,project,proposal);
        assertThat(result.analysisStatus()).isEqualTo("STALE"); assertThat(result.isCurrent()).isFalse();
        assertThat(result.errorCode()).isEqualTo("analysis_input_changed");
        error(() -> runs.retry(caller,project,proposal,null),"baseline_changed");
    }

    @Test void resubmissionMakesHistoryNonCurrentAndKeyReuseConflicts() {
        runs.retry(caller,project,proposal,"once"); work();
        var before=runs.get(caller,project,proposal);
        proposals.reject(CallerContext.human(api.accountId("leader@test")),project,proposal,
                new RejectRequest("보완",1,before.snapshotHash()),"reject");
        proposals.resubmit(caller,project,proposal,new Input(""+baseline,"revised","보완",null,null,
                List.of(new Item("RETIRE",""+api.designItemId("API-01"),""+api.currentVersionId("API-01"),null,null,null)),
                List.of(),1,before.snapshotHash(),null),"resubmit");
        var historical=runs.get(caller,project,proposal);
        assertThat(historical.revision()).isEqualTo(1); assertThat(historical.isCurrent()).isFalse();
        assertThat(historical.staleReason()).isEqualTo("proposal_revision_changed");
        error(() -> runs.retry(caller,project,proposal,"once"),"idempotency_conflict");
        runs.retry(caller,project,proposal,"new"); work();
        assertThat(runs.get(caller,project,proposal).revision()).isEqualTo(2);
    }

    @Test void missingCandidatesAreNotEvaluatedAndDoNotCallAi() {
        var created=proposals.create(caller,project,new Input(""+baseline,"ZzZ","ZzZ",null,null,
                List.of(new Item("CREATE",null,null,"REQUIREMENT",1,Map.of("description","ZzZ"))),List.of(),null,null,null),"empty");
        long id=Long.parseLong(created.id());
        runs.retry(caller,project,id,null); work();
        var result=runs.get(caller,project,id);
        assertThat(result.analysisStatus()).isEqualTo("NOT_EVALUATED");
        assertThat(result.errorCode()).isEqualTo("analysis_candidates_unavailable");
        verifyNoInteractions(ai);
    }

    @Test void oversizedModelResultPreservesReportedUsageAndRules() {
        var candidate=Map.of("design_item_id",""+api.designItemId("API-01"),"reason","x".repeat(1_048_577),"sources",List.of());
        when(ai.changeImpact(any())).thenReturn(Map.of("result",Map.of("candidates",List.of(candidate),"evidence_status","SUFFICIENT"),
                "usage",List.of(Map.of("request_key","received-call","failed",false))));
        runs.retry(caller,project,proposal,null); work();
        assertThat(runs.get(caller,project,proposal).errorCode()).isEqualTo("invalid_output");
        assertThat(api.jdbc.sql("SELECT usage_status FROM change_impact_run WHERE change_proposal_id=:p")
                .param("p",proposal).query(String.class).single()).isEqualTo("REPORTED");
    }

    @Test void aLateModelResultCannotOverwriteAnExpiredRunAfterRetry() {
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(ai.changeImpact(any())).thenAnswer(call -> {
            if (first.getAndSet(false)) {
                api.jdbc.sql("UPDATE change_impact_run SET lease_until=clock_timestamp()-interval '1 second' WHERE status='RUNNING'").update();
                runs.retry(caller,project,proposal,"replacement");
            }
            return envelope("SUFFICIENT");
        });
        runs.retry(caller,project,proposal,"old"); work();
        assertThat(runs.get(caller,project,proposal).errorCode()).isEqualTo("analysis_interrupted");
        work(); assertThat(runs.get(caller,project,proposal).analysisStatus()).isEqualTo("COMPLETED");
        assertThat(api.jdbc.sql("SELECT status FROM change_impact_run WHERE change_proposal_id=:p ORDER BY id")
                .param("p",proposal).query(String.class).list()).containsExactly("FAILED","COMPLETED");
    }
}
