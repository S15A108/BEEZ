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
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real Kafka listener + PostgreSQL + dispatcher; AI is a deterministic test double. */
@EnabledIfEnvironmentVariable(named="BEEZ_227_KAFKA_BOOTSTRAP",matches=".+")
class PublishedAnalysisKafkaIntegrationTest extends IntegrationTestSupport {
    @DynamicPropertySource static void cdc(DynamicPropertyRegistry p) {
        p.add("beez.cdc.intake.enabled", () -> "true");
        p.add("beez.cdc.intake.bootstrap-servers", () -> System.getenv("BEEZ_227_KAFKA_BOOTSTRAP"));
        p.add("beez.cdc.intake.topic", () -> System.getenv("BEEZ_227_KAFKA_TOPIC"));
        p.add("beez.cdc.intake.group-id", () -> System.getenv("BEEZ_227_KAFKA_GROUP"));
    }
    @Autowired DataSource dataSource;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @MockitoBean AiClient ai;

    @Test void committedPublicationIsDispatchedFromKafkaAndReplayDoesNotDuplicateAnalysis() throws Exception {
        var api=new TaskApiSupport(this::client,dataSource); api.resetAndLoadFixture();
        long project=api.projectId(),leader=api.accountId("leader@test");
        long base=api.jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p").param("p",project).query(Long.class).single();
        api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status,finished_at) "
                + "VALUES(:p,:b,'NOT_EVALUATED',clock_timestamp())").param("p",project).param("b",base).update();
        var caller=CallerContext.human(leader);
        var proposal=proposals.create(caller,project,new Input(""+base,"API 변경","사용자 조회",null,null,
                List.of(new Item("UPDATE",""+api.designItemId("API-01"),""+api.currentVersionId("API-01"),"API_CONTRACT",1,
                        Map.of("method","GET","path","/users/v2","description","사용자 조회 변경"))),List.of(),null,null,null),"kafka-create");
        var published=publications.approve(caller,project,Long.parseLong(proposal.id()),
                new ApproveRequest(proposal.baseBaselineId(),proposal.revision(),proposal.snapshotHash()),"kafka-approve");
        long id=api.jdbc.sql("SELECT id FROM published_change_analysis WHERE published_baseline_id=:b")
                .param("b",Long.parseLong(published.baseline().id())).query(Long.class).single();
        when(ai.changeImpact(any())).thenReturn(Map.of("result",Map.of("candidates",List.of(),"evidence_status","SUFFICIENT"),
                "usage",List.of(Map.of("request_key","test","feature","CHANGE_IMPACT","failed",false))));
        String broker=System.getenv("BEEZ_227_KAFKA_BOOTSTRAP"),topic=System.getenv("BEEZ_227_KAFKA_TOPIC"),group=System.getenv("BEEZ_227_KAFKA_GROUP");
        var partition=new TopicPartition(topic,0);
        await(() -> listeners.getListenerContainers().stream().anyMatch(c -> c.getAssignedPartitions()!=null
                && c.getAssignedPartitions().contains(partition)),30);
        Properties props=new Properties(); props.put("bootstrap.servers",broker);
        props.put("key.serializer",StringSerializer.class.getName()); props.put("value.serializer",StringSerializer.class.getName());
        String value="{\"payload\":{\"source\":{\"schema\":\"public\",\"table\":\"published_change_analysis\"},"
                +"\"op\":\"c\",\"after\":{\"id\":"+id+",\"status\":\"PENDING\"}}}";
        try(var producer=new KafkaProducer<String,String>(props); var admin=Admin.create(Map.of("bootstrap.servers",broker))) {
            producer.send(new ProducerRecord<>(topic,"project",value)).get(15,TimeUnit.SECONDS);
            var duplicate=producer.send(new ProducerRecord<>(topic,"project",value)).get(15,TimeUnit.SECONDS);
            await(() -> "COMPLETED".equals(api.jdbc.sql("SELECT status FROM published_change_analysis WHERE id=:id")
                    .param("id",id).query(String.class).single()),30);
            await(() -> {
                try {
                    var offsets=admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS);
                    return offsets.get(partition)!=null && offsets.get(partition).offset()>duplicate.offset();
                } catch (Exception failure) { return false; }
            },30);
        }
        assertThat(api.jdbc.sql("SELECT evaluation_status FROM published_change_analysis WHERE id=:id").param("id",id)
                .query(String.class).single()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(api.jdbc.sql("SELECT count(*) FROM published_analysis_attempt WHERE intake_id=:id").param("id",id)
                .query(Integer.class).single()).isEqualTo(1);
        verify(ai,times(1)).changeImpact(any());

        // A second committed publication has no Kafka signal. Recovery must find it.
        long secondBase=Long.parseLong(published.baseline().id());
        var next=proposals.create(caller,project,new Input(""+secondBase,"두 번째 API 변경","사용자 조회",null,null,
                List.of(new Item("UPDATE",""+api.designItemId("API-01"),""+api.currentVersionId("API-01"),"API_CONTRACT",1,
                        Map.of("method","GET","path","/users/v3","description","사용자 조회 재변경"))),List.of(),null,null,null),"recovery-create");
        var nextPublished=publications.approve(caller,project,Long.parseLong(next.id()),
                new ApproveRequest(next.baseBaselineId(),next.revision(),next.snapshotHash()),"recovery-approve");
        long recoveryId=api.jdbc.sql("SELECT id FROM published_change_analysis WHERE published_baseline_id=:b")
                .param("b",Long.parseLong(nextPublished.baseline().id())).query(Long.class).single();
        api.jdbc.sql("UPDATE published_change_analysis SET requested_at=clock_timestamp()-interval '40 seconds' WHERE id=:id")
                .param("id",recoveryId).update();
        await(() -> "COMPLETED".equals(api.jdbc.sql("SELECT status FROM published_change_analysis WHERE id=:id")
                .param("id",recoveryId).query(String.class).single()),30);
        assertThat(api.jdbc.sql("SELECT evaluation_status FROM published_change_analysis WHERE id=:id").param("id",recoveryId)
                .query(String.class).single()).isEqualTo("INSUFFICIENT_EVIDENCE");
        verify(ai,times(2)).changeImpact(any());
    }

    private static void await(java.util.function.BooleanSupplier condition,int seconds) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime()>deadline) throw new AssertionError("Timed out waiting for Kafka integration");
            Thread.sleep(100);
        }
    }
}
