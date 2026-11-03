package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
import com.beez.backend.cdc.transport.AnalysisIntakeKafkaListener;
import com.beez.backend.cdc.transport.PublishedAnalysisRecordDecoder;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

class PublishedAnalysisExecutionIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired PublishedAnalysisRepository repository;
    @Autowired PublishedAnalysisProcessor processor;
    @MockitoBean AiClient ai;
    TaskApiSupport api;
    long project, leader, initial, oldVersion, intake;

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
        var request = new ApproveRequest(proposal.baseBaselineId(),proposal.revision(),proposal.snapshotHash());
        var published = publications.approve(caller,project,Long.parseLong(proposal.id()),request,"approve");
        intake = api.jdbc.sql("SELECT id FROM published_change_analysis WHERE published_baseline_id=:b")
                .param("b",Long.parseLong(published.baseline().id())).query(Long.class).single();
        when(ai.changeImpact(any())).thenReturn(envelope());
    }

    private Map<String,Object> envelope() {
        return Map.of("result",Map.of("candidates",List.of(),"evidence_status","SUFFICIENT"),"origin","MODEL",
                "usage",List.of(Map.of("request_key","published-llm-call","feature","CHANGE_IMPACT","failed",false)));
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
