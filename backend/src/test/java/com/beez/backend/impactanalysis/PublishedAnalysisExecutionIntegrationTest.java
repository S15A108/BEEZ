package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static com.beez.backend.designitem.support.DesignJson.JSON;

import com.beez.backend.ai.AiClient;
import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
import com.beez.backend.cdc.transport.AnalysisIntakeKafkaListener;
import com.beez.backend.cdc.transport.PublishedAnalysisRecordDecoder;
import com.beez.backend.cdc.observability.PublishedAnalysisMetricsReader;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class PublishedAnalysisExecutionIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired PublishedAnalysisRepository repository;
    @Autowired PublishedAnalysisProcessor processor;
    @Autowired PublishedAnalysisMetricsReader metricsReader;
    @MockitoBean AiClient ai;
    TaskApiSupport api;
    long project, leader, initial, oldVersion, intake, proposalId;

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); leader = api.accountId("leader@test");
        initial = api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p",project).query(Long.class).single();
        oldVersion = api.currentVersionId("API-01");
        api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status,finished_at) "
                + "VALUES(:p,:b,'NOT_EVALUATED',clock_timestamp())").param("p",project).param("b",initial).update();
        long member = api.accountId("member@test");
        assertThat(api.post("/api/projects/"+project+"/tasks", api.planJson("사용자 API 검토",api.readsRef("API-01"),"[]"), member)
                .statusCode()).isEqualTo(201);
        var caller = CallerContext.human(leader);
        var proposal = proposals.create(caller,project,new Input(""+initial,"사용자 API 변경","사용자 경로 변경",null,null,
                List.of(new Item("UPDATE",""+api.designItemId("API-01"),""+oldVersion,"API_CONTRACT",1,
                        Map.of("method","GET","path","/users/v2","description","사용자 조회 변경"))),List.of(),null,null,null),"create");
        var request = new ApproveRequest(proposal.baseBaselineId(),proposal.revision(),proposal.snapshotHash(),
                "발행 후 분석 테스트를 위해 승인 전 분석을 생략함");
        proposalId = Long.parseLong(proposal.id());
        var published = publications.approve(caller,project,Long.parseLong(proposal.id()),request,"approve");
        intake = api.jdbc.sql("SELECT id FROM published_change_analysis WHERE published_baseline_id=:b")
                .param("b",Long.parseLong(published.baseline().id())).query(Long.class).single();
        when(ai.changeImpact(any())).thenReturn(envelope());
    }

    private Map<String,Object> envelope() {
        return Map.of("result",Map.of("candidates",List.of(),"evidence_status","SUFFICIENT"),"origin","MODEL",
                "usage",List.of(Map.of("request_key","published-llm-call","feature","CHANGE_IMPACT","failed",false)));
    }

    @Test void proposalDetailShowsAuthorizedPublicationStateAndDoesNotCallInsufficientEvidenceAnEmptySuccess() {
        String path = "/api/projects/" + project + "/change-proposals/" + proposalId;
        var member = api.accountId("member@test");
        var outsider = api.accountId("outsider@test");
        var pending = api.get(path, member);
        assertThat(pending.statusCode()).as(pending.body()).isEqualTo(200);
        assertThat(JSON.readTree(pending.body()).get("published_analysis").get("status").asText()).isEqualTo("PENDING");
        assertThat(api.get(path, outsider).statusCode()).isEqualTo(404);

        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("secret failure")).thenReturn(envelope());
        processor.process(intake);
        var retry = JSON.readTree(api.get(path, member).body()).get("published_analysis");
        assertThat(retry.get("status").asText()).isEqualTo("PENDING");
        assertThat(retry.get("attempt").asInt()).isEqualTo(1);
        assertThat(retry.get("guidance").asText()).isEqualTo("RETRY_SCHEDULED");
        assertThat(retry.get("next_attempt_at").asText()).isNotBlank();

        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", intake).update();
        processor.process(intake);
        var complete = JSON.readTree(api.get(path, member).body()).get("published_analysis");
        assertThat(complete.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(complete.get("evaluation_status").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(complete.get("guidance").asText()).isEqualTo("REVIEW_EVIDENCE");
        assertThat(complete.get("current_baseline").asBoolean()).isTrue();
        assertThat(complete.toString()).doesNotContain("secret failure");
    }

    @Test void missingPublishedIntakeIsUnknownRatherThanSuccessOrZeroImpact() {
        api.jdbc.sql("DELETE FROM published_change_analysis WHERE id=:id").param("id", intake).update();
        String path = "/api/projects/" + project + "/change-proposals/" + proposalId;
        var response = api.get(path, leader);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var status = JSON.readTree(response.body()).get("published_analysis");
        assertThat(status.get("status").asText()).isEqualTo("UNKNOWN");
        assertThat(status.get("guidance").asText()).isEqualTo("CHECK_RESULT");
    }

    @Test void metricsReflectRetryAndStoredEvaluationResults() {
        var initialMetrics = metricsReader.read();
        assertThat(initialMetrics.intakes().get("PENDING")).isEqualTo(1);
        assertThat(initialMetrics.notEvaluatedExpected()).isEqualTo(1);
        assertThat(initialMetrics.notEvaluatedRequiresReview()).isZero();

        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("secret failure")).thenReturn(envelope());
        processor.process(intake);
        var failure = metricsReader.read();
        assertThat(failure.pendingRetries()).isEqualTo(1);
        assertThat(failure.attempts().get("FAILED")).isEqualTo(1);
        assertThat(failure.startedRetries()).isZero();

        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", intake).update();
        processor.process(intake);
        var success = metricsReader.read();
        assertThat(success.intakes().get("PENDING")).isZero();
        assertThat(success.intakes().get("COMPLETED")).isEqualTo(1);
        assertThat(success.evaluations().get("INSUFFICIENT_EVIDENCE")).isEqualTo(1);
        assertThat(success.attempts().get("FAILED")).isEqualTo(1);
        assertThat(success.attempts().get("INSUFFICIENT_EVIDENCE")).isEqualTo(1);
        assertThat(success.startedRetries()).isEqualTo(1);
        assertThat(success.recordedRetryAttempts()).isEqualTo(1);
    }

    @Test void blockedProjectMetricFollowsTheWorkerPredecessorRule() {
        long next = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,3,:a) RETURNING id")
                .param("p", project).param("a", leader).query(Long.class).single();
        api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status) VALUES(:p,:b,'PENDING')")
                .param("p", project).param("b", next).update();
        api.jdbc.sql("UPDATE published_change_analysis SET status='FAILED',error_code='analysis_interrupted' WHERE id=:id")
                .param("id", intake).update();
        assertThat(metricsReader.read().blockedProjects()).isEqualTo(1);

        // COMPLETED with insufficient evidence still satisfies the existing ordering rule.
        api.jdbc.sql("UPDATE published_change_analysis SET status='COMPLETED',evaluation_status='INSUFFICIENT_EVIDENCE',error_code=NULL WHERE id=:id")
                .param("id", intake).update();
        assertThat(metricsReader.read().blockedProjects()).isZero();

        api.jdbc.sql("UPDATE published_change_analysis SET status='NOT_EVALUATED',evaluation_status='NOT_EVALUATED',error_code=NULL WHERE id=:id")
                .param("id", intake).update();
        assertThat(metricsReader.read().blockedProjects()).isEqualTo(1);
        assertThat(metricsReader.read().notEvaluatedRequiresReview()).isEqualTo(1);

        api.jdbc.sql("UPDATE published_change_analysis SET error_code='published_before_intake' WHERE id=:id")
                .param("id", intake).update();
        assertThat(metricsReader.read().blockedProjects()).isZero();
        assertThat(metricsReader.read().notEvaluatedExpected()).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    @Test void publicationEventRunsOnceWithHistoricalInputsAndExplicitInsufficientCoverage() {
        when(ai.changeImpact(any())).thenAnswer(call -> {
            Map<String,Object> request = call.getArgument(0);
            assertThat(request).containsEntry("analysis_timing","POST_PUBLICATION")
                    .containsEntry("approved_items_baseline","BEFORE_PUBLICATION");
            var approved = (List<Map<String,Object>>)request.get("approved_items");
            assertThat(approved).anySatisfy(item -> {
                assertThat(item.get("id")).isEqualTo(""+oldVersion);
                assertThat((Map<String,Object>)item.get("content")).containsEntry("path","/users");
            });
            return envelope();
        });
        var handler = new AnalysisIntakeSignalHandler(ref -> {
            repository.ensureSafeToAcknowledge(ref);
            processor.process(Long.parseLong(ref));
        });
        var listener = new AnalysisIntakeKafkaListener(new PublishedAnalysisRecordDecoder(),handler);
        String event = "{\"source\":{\"schema\":\"public\",\"table\":\"published_change_analysis\"},"
                + "\"op\":\"c\",\"after\":{\"id\":"+intake+",\"status\":\"PENDING\"}}";
        listener.receive(new ConsumerRecord<>("intake",0,0L,"project",event));
        listener.receive(new ConsumerRecord<>("intake",0,1L,"project",event));
        var row = api.jdbc.sql("SELECT * FROM published_change_analysis WHERE id=:id").param("id",intake).query().singleRow();
        assertThat(row).containsEntry("status","COMPLETED").containsEntry("evaluation_status","INSUFFICIENT_EVIDENCE")
                .containsEntry("usage_status","REPORTED");
        assertThat(row.get("explicit_result").toString()).contains("task_id");
        assertThat(row.get("ai_result").toString()).contains("candidates");
        assertThat(api.jdbc.sql("SELECT count(*) FROM published_analysis_attempt WHERE intake_id=:id").param("id",intake)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(api.jdbc.sql("SELECT count(*) FROM change_impact_run").query(Integer.class).single()).isZero();
        // 비용 조회(MON-A02)가 읽는 공통 사용량 표에도 한 번만 남는다 (같은 이벤트를 두 번 받아도)
        assertThat(api.jdbc.sql("SELECT project_id,feature,model,task_id FROM llm_usage WHERE request_key='published-llm-call'")
                .query().listOfRows()).singleElement().satisfies(usage -> assertThat(usage)
                        .containsEntry("project_id",project).containsEntry("feature","CHANGE_IMPACT")
                        .containsEntry("model","unknown").containsEntry("task_id",null));
        verify(ai,times(1)).changeImpact(any());
    }

    @Test void transientFailureIsRecordedAndRetryUsesSameDurableIntake() {
        when(ai.changeImpact(any())).thenThrow(new ResourceAccessException("secret failure")).thenReturn(envelope());
        processor.process(intake);
        assertThat(api.jdbc.sql("SELECT status FROM published_change_analysis WHERE id=:id").param("id",intake)
                .query(String.class).single()).isEqualTo("PENDING");
        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id",intake).update();
        processor.process(intake);
        assertThat(api.jdbc.sql("SELECT outcome FROM published_analysis_attempt WHERE intake_id=:id ORDER BY attempt")
                .param("id",intake).query(String.class).list()).containsExactly("FAILED","INSUFFICIENT_EVIDENCE");
        assertThat(api.jdbc.sql("SELECT attempt FROM published_change_analysis WHERE id=:id").param("id",intake)
                .query(Integer.class).single()).isEqualTo(2);
    }

    @Test void expiredWorkerCannotOverwriteReclaimedAttempt() {
        when(ai.changeImpact(any())).thenAnswer(call -> {
            api.jdbc.sql("UPDATE published_change_analysis SET lease_until=clock_timestamp()-interval '1 second' WHERE id=:id")
                    .param("id",intake).update();
            assertThat(repository.claimNow(intake,60)).isPresent();
            return envelope();
        });
        processor.process(intake);
        var row=api.jdbc.sql("SELECT status,attempt,ai_result FROM published_change_analysis WHERE id=:id").param("id",intake)
                .query().singleRow();
        assertThat(row).containsEntry("status","RUNNING").containsEntry("attempt",2).containsEntry("ai_result",null);
        assertThat(api.jdbc.sql("SELECT count(*) FROM published_analysis_attempt WHERE intake_id=:id").param("id",intake)
                .query(Integer.class).single()).isZero();
    }

    @Test void accessRevokedAfterPublicationDoesNotInvokeModel() {
        api.jdbc.sql("UPDATE project_member SET active=false,left_at=clock_timestamp() WHERE project_id=:p AND account_id=:a")
                .param("p",project).param("a",leader).update();
        processor.process(intake);
        var row=api.jdbc.sql("SELECT status,error_code FROM published_change_analysis WHERE id=:id").param("id",intake)
                .query().singleRow();
        assertThat(row).containsEntry("status","FAILED").containsEntry("error_code","analysis_access_unavailable");
        verify(ai,never()).changeImpact(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void accessRevokedDuringModelCallPreservesReceivedUsageOnly(boolean modelError) {
        when(ai.changeImpact(any())).thenAnswer(call -> {
            api.jdbc.sql("UPDATE project_member SET active=false,left_at=clock_timestamp() WHERE project_id=:p AND account_id=:a")
                    .param("p",project).param("a",leader).update();
            if (modelError) {
                byte[] body = ("{\"error\":{\"code\":\"model_timeout\"},\"usage\":[{\"request_key\":\"published-llm-call\","
                        + "\"feature\":\"CHANGE_IMPACT\",\"failed\":false}]}").getBytes(StandardCharsets.UTF_8);
                throw new RestClientResponseException("model failure", 504, "Gateway Timeout", null, body, StandardCharsets.UTF_8);
            }
            return envelope();
        });

        processor.process(intake);

        var row = api.jdbc.sql("SELECT status,error_code,evaluation_status,explicit_result,ai_result,generation "
                + "FROM published_change_analysis WHERE id=:id").param("id",intake).query().singleRow();
        assertThat(row).containsEntry("status","FAILED").containsEntry("error_code","analysis_access_unavailable")
                .containsEntry("evaluation_status",null).containsEntry("explicit_result",null)
                .containsEntry("ai_result",null).containsEntry("generation",null);
        var attempt = api.jdbc.sql("SELECT attempt,outcome,error_code,usage_status, "
                + "usage = '[{\"request_key\":\"published-llm-call\",\"feature\":\"CHANGE_IMPACT\",\"failed\":false}]'::jsonb AS usage_matches "
                + "FROM published_analysis_attempt WHERE intake_id=:id").param("id",intake).query().singleRow();
        assertThat(attempt).containsEntry("attempt",1).containsEntry("outcome","FAILED")
                .containsEntry("error_code","analysis_access_unavailable").containsEntry("usage_status","REPORTED")
                .containsEntry("usage_matches",true);
        // 결과를 버린 시도라도 실제 호출 비용은 공통 사용량 표에 남는다
        assertThat(api.jdbc.sql("SELECT count(*) FROM llm_usage WHERE request_key='published-llm-call' AND feature='CHANGE_IMPACT'")
                .query(Integer.class).single()).isEqualTo(1);
        verify(ai,times(1)).changeImpact(any());
    }

    @Test void aNewBaselineDuringAnalysisNeverStoresTheOldCandidatesAsCurrent() {
        when(ai.changeImpact(any())).thenAnswer(call -> {
            long next=api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,3,:a) RETURNING id")
                    .param("p",project).param("a",leader).query(Long.class).single();
            api.jdbc.sql("UPDATE project SET current_baseline_id=:b WHERE id=:p").param("b",next).param("p",project).update();
            return envelope();
        });
        processor.process(intake);
        var row=api.jdbc.sql("SELECT status,ai_result FROM published_change_analysis WHERE id=:id").param("id",intake)
                .query().singleRow();
        assertThat(row).containsEntry("status","PENDING").containsEntry("ai_result",null);
        assertThat(api.jdbc.sql("SELECT usage_status FROM published_analysis_attempt WHERE intake_id=:id AND attempt=1")
                .param("id",intake).query(String.class).single()).isEqualTo("REPORTED");
        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id",intake).update();
        processor.process(intake);
        assertThat(api.jdbc.sql("SELECT error_code FROM published_change_analysis WHERE id=:id").param("id",intake)
                .query(String.class).single()).isEqualTo("baseline_changed");
        verify(ai,times(1)).changeImpact(any());
    }
}
