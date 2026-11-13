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

    /**
     * 알림은 다시 손쓸 수 있는 FAILED·확인 필요 미평가만 센다. 뒤 버전이 발행됐거나(현재 baseline 이 아님) 프로젝트가 보관된
     * 행은 다시 분석할 수 없으므로 따로 센다. 전체 수(intakes·not_evaluated_requires_review)는 그대로다 (S15P31A108-412).
     */
    @Test void unresolvedRowsAreSplitByWhetherTheirBaselineIsStillCurrent() {
        api.jdbc.sql("UPDATE published_change_analysis SET status='FAILED',attempt=4,error_code='model_unavailable' WHERE id=:id")
                .param("id", intake).update();
        assertThat(metricsReader.read().unresolved())
                .containsEntry("FAILED/ACTIONABLE", 1d).containsEntry("FAILED/NOT_ACTIONABLE", 0d);

        api.jdbc.sql("UPDATE published_change_analysis SET status='NOT_EVALUATED',evaluation_status='NOT_EVALUATED',"
                + "error_code='document_change_unavailable' WHERE id=:id").param("id", intake).update();
        assertThat(metricsReader.read().unresolved())
                .containsEntry("NOT_EVALUATED_REVIEW/ACTIONABLE", 1d).containsEntry("FAILED/ACTIONABLE", 0d);

        // 뒤 버전이 발행되면 같은 행이 손쓸 수 없는 쪽으로 옮겨 간다. 최초 v1(예상된 미평가)은 어느 쪽에도 세지 않는다
        long next = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,3,:a) RETURNING id")
                .param("p", project).param("a", leader).query(Long.class).single();
        api.jdbc.sql("UPDATE project SET current_baseline_id=:b WHERE id=:p").param("b", next).param("p", project).update();
        var superseded = metricsReader.read();
        assertThat(superseded.unresolved())
                .containsEntry("NOT_EVALUATED_REVIEW/ACTIONABLE", 0d).containsEntry("NOT_EVALUATED_REVIEW/NOT_ACTIONABLE", 1d);
        assertThat(superseded.notEvaluatedRequiresReview()).isEqualTo(1);

        // 보관된 프로젝트의 현재 버전도 다시 분석할 수 없다(analysis_access_unavailable)
        api.jdbc.sql("UPDATE project SET current_baseline_id=:b WHERE id=:p")
                .param("b", api.jdbc.sql("SELECT published_baseline_id FROM published_change_analysis WHERE id=:id")
                        .param("id", intake).query(Long.class).single()).param("p", project).update();
        api.jdbc.sql("UPDATE project SET archived_at=clock_timestamp() WHERE id=:p").param("p", project).update();
        assertThat(metricsReader.read().unresolved())
                .containsEntry("NOT_EVALUATED_REVIEW/ACTIONABLE", 0d).containsEntry("NOT_EVALUATED_REVIEW/NOT_ACTIONABLE", 1d);
    }

    /**
     * 앞 버전은 접수행이 없거나 아직 PENDING·RUNNING일 때만 뒤 버전을 막는다. FAILED·미평가로 끝난 앞 버전은 뒤 버전이
     * 발행된 뒤에는 다시 분석할 수 없으므로(baseline_changed) 기다리면 그 프로젝트가 영구히 멈춘다.
     * 워커 선점과 막힘 지표가 같은 판정을 쓰는지 함께 본다 (S15P31A108-408).
     */
    @Test void earlierVersionBlocksOnlyWhileMissingPendingOrRunning() {
        long nextIntake = pendingLaterPublication();
        for (String waiting : List.of("status='PENDING',attempt=0,lease_until=NULL",
                "status='RUNNING',attempt=1,lease_until=clock_timestamp()+interval '1 minute'")) {
            setEarlier(waiting);
            assertThat(metricsReader.read().blockedProjects()).as(waiting).isEqualTo(1);
            assertThat(repository.claimNow(nextIntake, 60)).as(waiting).isEmpty();
        }
        for (String finished : List.of(
                "status='FAILED',attempt=4,lease_until=NULL,error_code='model_unavailable'",
                "status='FAILED',attempt=1,lease_until=NULL,error_code='baseline_changed'",
                "status='NOT_EVALUATED',attempt=1,lease_until=NULL,evaluation_status='NOT_EVALUATED',error_code='change_content_unavailable'",
                "status='NOT_EVALUATED',attempt=1,lease_until=NULL,evaluation_status='NOT_EVALUATED',error_code='document_change_unavailable'",
                "status='COMPLETED',attempt=1,lease_until=NULL,evaluation_status='INSUFFICIENT_EVIDENCE',error_code=NULL")) {
            setEarlier(finished);
            assertThat(metricsReader.read().blockedProjects()).as(finished).isZero();
            assertThat(repository.claimNow(nextIntake, 60)).as(finished).isPresent();
            api.jdbc.sql("UPDATE published_change_analysis SET status='PENDING',attempt=0,lease_until=NULL,started_at=NULL WHERE id=:id")
                    .param("id", nextIntake).update();
        }

        // 막지 않아도 끝난 앞 버전의 미평가는 확인 필요 지표에 그대로 남는다
        setEarlier("status='NOT_EVALUATED',attempt=1,lease_until=NULL,evaluation_status='NOT_EVALUATED',error_code='change_content_unavailable'");
        assertThat(metricsReader.read().notEvaluatedRequiresReview()).isEqualTo(1);

        // 앞 버전의 접수행이 없으면(데이터 이상) 지금처럼 막는다
        api.jdbc.sql("DELETE FROM published_change_analysis WHERE id=:id").param("id", intake).update();
        assertThat(metricsReader.read().blockedProjects()).isEqualTo(1);
        assertThat(repository.claimNow(nextIntake, 60)).isEmpty();
    }

    private void setEarlier(String assignments) {
        api.jdbc.sql("UPDATE published_change_analysis SET " + assignments + " WHERE id=:id").param("id", intake).update();
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

    private static RestClientResponseException truncated() {
        byte[] body = ("{\"error\":{\"code\":\"model_output_truncated\"},\"usage\":[{\"request_key\":\"published-llm-call\","
                + "\"feature\":\"CHANGE_IMPACT\",\"failed\":true}]}").getBytes(StandardCharsets.UTF_8);
        return new RestClientResponseException("output truncated", 502, "Bad Gateway", null, body, StandardCharsets.UTF_8);
    }

    /**
     * 선점 순서 검사 전용 후속 발행 행. 기준 v3 과 접수행만 SQL 로 넣고 project.current_baseline_id 는 바꾸지 않는다.
     * 실제 발행이 먼저 끝나면 현재 기준이 바뀌어 선행 건의 재시도는 기존 baseline_changed 규칙(ImpactContextQuery)에 걸리므로,
     * 이 행으로는 "선행 건이 끝나야 후속 건을 집는다"만 본다 (#286 리뷰).
     */
    private long pendingLaterPublication() {
        long nextBaseline = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,3,:a) RETURNING id")
                .param("p", project).param("a", leader).query(Long.class).single();
        return api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status) "
                + "VALUES(:p,:b,'PENDING') RETURNING id").param("p", project).param("b", nextBaseline).query(Long.class).single();
    }

    /**
     * 출력 상한 잘림을 첫 시도에 최종 FAILED 로 닫으면 같은 프로젝트의 후속 발행 분석이 선행 규칙에 막혀 멈춘다.
     * 잘림은 코드를 그대로 남긴 채 한 번 더 시도하고, 성공하면 후속 발행 분석을 집을 수 있다 (S15P31A108-402, #286 리뷰).
     */
    @Test void truncatedOutputRetriesOnceKeepingItsCodeAndThenUnblocksTheNextPublication() {
        long nextIntake = pendingLaterPublication();
        when(ai.changeImpact(any())).thenThrow(truncated()).thenReturn(envelope());

        processor.process(intake);
        assertThat(api.jdbc.sql("SELECT status,error_code,next_attempt_at IS NOT NULL AS scheduled FROM published_change_analysis WHERE id=:id")
                .param("id", intake).query().singleRow())
                .containsEntry("status", "PENDING").containsEntry("error_code", "model_output_truncated").containsEntry("scheduled", true);
        assertThat(repository.claimNow(nextIntake, 60)).isEmpty();

        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", intake).update();
        processor.process(intake);
        // 시도별로 본다. 첫 시도는 잘림 코드 그대로, 두 번째는 평가 결과만(사유 코드는 관계·검색 범위에 따라 붙으므로 이 시험의 대상이 아니다)
        var attempts = api.jdbc.sql("SELECT attempt,outcome,error_code FROM published_analysis_attempt WHERE intake_id=:id ORDER BY attempt")
                .param("id", intake).query().listOfRows();
        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0)).containsEntry("attempt", 1).containsEntry("outcome", "FAILED")
                .containsEntry("error_code", "model_output_truncated");
        assertThat(attempts.get(1)).containsEntry("attempt", 2).containsEntry("outcome", "INSUFFICIENT_EVIDENCE");
        assertThat(api.jdbc.sql("SELECT status FROM published_change_analysis WHERE id=:id").param("id", intake)
                .query(String.class).single()).isEqualTo("COMPLETED");
        assertThat(repository.claimNow(nextIntake, 60)).isPresent();
    }

    /** 잘림이 다시 나면 같은 상한으로 계속 끊길 가능성이 커 더 부르지 않는다. 유료 호출은 최대 두 번이다. */
    @Test void truncatedOutputTwiceEndsFailedWithoutFurtherModelCalls() {
        when(ai.changeImpact(any())).thenThrow(truncated()).thenThrow(truncated());
        processor.process(intake);
        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", intake).update();
        processor.process(intake);

        assertThat(api.jdbc.sql("SELECT status,error_code,attempt,next_attempt_at FROM published_change_analysis WHERE id=:id")
                .param("id", intake).query().singleRow())
                .containsEntry("status", "FAILED").containsEntry("error_code", "model_output_truncated")
                .containsEntry("attempt", 2).containsEntry("next_attempt_at", null);
        verify(ai, times(2)).changeImpact(any());
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

    /**
     * 분석 중 뒤 버전이 발행되면 앞 버전은 재시도에서 baseline_changed 로 끝난다. 뒤 버전은 앞 버전이 끝나기까지만 기다리고
     * 그 뒤 바로 선점된다. 전에는 끝난 앞 버전(FAILED)을 계속 기다려 그 프로젝트의 발행 후 분석이 영구히 멈췄다
     * (dev 프로젝트 20: v2 FAILED 뒤 v3·v4 PENDING 고착, S15P31A108-408).
     */
    @Test void aVersionSupersededDuringAnalysisDoesNotBlockTheLaterPublication() {
        long[] later = new long[1];
        when(ai.changeImpact(any())).thenAnswer(call -> {
            long next = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,3,:a) RETURNING id")
                    .param("p", project).param("a", leader).query(Long.class).single();
            api.jdbc.sql("UPDATE project SET current_baseline_id=:b WHERE id=:p").param("b", next).param("p", project).update();
            later[0] = api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status) "
                    + "VALUES(:p,:b,'PENDING') RETURNING id").param("p", project).param("b", next).query(Long.class).single();
            return envelope();
        });
        processor.process(intake);
        // 앞 버전이 재시도를 기다리는 동안에는 순서대로 기다린다
        assertThat(repository.claimNow(later[0], 60)).isEmpty();

        api.jdbc.sql("UPDATE published_change_analysis SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:id")
                .param("id", intake).update();
        processor.process(intake);
        assertThat(api.jdbc.sql("SELECT status,error_code FROM published_change_analysis WHERE id=:id").param("id", intake)
                .query().singleRow()).containsEntry("status", "FAILED").containsEntry("error_code", "baseline_changed");
        assertThat(metricsReader.read().blockedProjects()).isZero();
        assertThat(repository.claimNow(later[0], 60)).isPresent();
    }
}
