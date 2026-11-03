package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisDispatcher;
import com.beez.backend.impactanalysis.intake.PublishedAnalysisRepository;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class PublishedAnalysisRecoveryIntegrationTest extends IntegrationTestSupport {
    @Autowired DataSource dataSource;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired PublishedAnalysisRepository repository;
    @Autowired PublishedAnalysisProcessor processor;
    @MockitoBean AiClient ai;

    @Test void recoveryFindsAnOldPendingIntakeWithoutAKafkaSignal() throws Exception {
        var api=new TaskApiSupport(this::client,dataSource); api.resetAndLoadFixture();
        long project=api.projectId(),leader=api.accountId("leader@test");
        long base=api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p",project).query(Long.class).single();
        api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status,finished_at) "
                + "VALUES(:p,:b,'NOT_EVALUATED',clock_timestamp())").param("p",project).param("b",base).update();
        var caller=CallerContext.human(leader);
        var proposal=proposals.create(caller,project,new Input(""+base,"API 변경","사용자 조회",null,null,
                List.of(new Item("UPDATE",""+api.designItemId("API-01"),""+api.currentVersionId("API-01"),"API_CONTRACT",1,
                        Map.of("method","GET","path","/users/v2","description","사용자 조회 변경"))),List.of(),null,null,null),"recovery-create");
        var published=publications.approve(caller,project,Long.parseLong(proposal.id()),
                new ApproveRequest(proposal.baseBaselineId(),proposal.revision(),proposal.snapshotHash()),"recovery-approve");
        long id=api.jdbc.sql("SELECT id FROM published_change_analysis WHERE published_baseline_id=:b")
                .param("b",Long.parseLong(published.baseline().id())).query(Long.class).single();
        when(ai.changeImpact(any())).thenReturn(Map.of("result",Map.of("candidates",List.of(),"evidence_status","SUFFICIENT"),
                "usage",List.of(Map.of("request_key","test","feature","CHANGE_IMPACT","failed",false))));
        api.jdbc.sql("UPDATE published_change_analysis SET requested_at=clock_timestamp()-interval '40 seconds' WHERE id=:id")
                .param("id",id).update();
        var dispatcher=new PublishedAnalysisDispatcher(repository,processor);
        try {
            dispatcher.recover();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (!"COMPLETED".equals(api.jdbc.sql("SELECT status FROM published_change_analysis WHERE id=:id")
                    .param("id",id).query(String.class).single())) {
                if (System.nanoTime()>deadline) throw new AssertionError("Recovery did not process the pending intake");
                Thread.sleep(50);
            }
            assertThat(api.jdbc.sql("SELECT evaluation_status FROM published_change_analysis WHERE id=:id").param("id",id)
                    .query(String.class).single()).isEqualTo("INSUFFICIENT_EVIDENCE");
            verify(ai,times(1)).changeImpact(any());
        } finally {
            dispatcher.destroy();
        }
    }
}
