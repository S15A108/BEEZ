package com.beez.backend.cdc.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.cdc.CdcKafkaSecurityProperties;
import com.beez.backend.cdc.intake.AnalysisIntakeChange;
import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.CommonClientConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerProperties;

class AnalysisIntakeKafkaTransportTest {
    private final AnalysisIntakeCheckPort check = mock(AnalysisIntakeCheckPort.class);
    private final AnalysisIntakeRecordDecoder decoder = mock(AnalysisIntakeRecordDecoder.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final AnalysisIntakeKafkaListener listener = new AnalysisIntakeKafkaListener(decoder,
            new AnalysisIntakeSignalHandler(check), metrics);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AnalysisIntakeKafkaConfiguration.class)
            .withBean(MeterRegistry.class, () -> metrics);

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("test-topic", 0, 0L, "project-key", value);
    }

    @Test
    void intakeTransportIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AnalysisIntakeKafkaListener.class);
        });
    }

    @Test
    void enablingWithoutDecoderOrDurableAdapterFailsAtStartup() {
        var enabled = contextRunner.withPropertyValues(
                "beez.cdc.intake.enabled=true", "beez.cdc.intake.consumer-enabled=true",
                "beez.cdc.intake.bootstrap-servers=localhost:9092",
                "beez.cdc.intake.group-id=test-group", "beez.cdc.intake.topic=test-topic");
        enabled.withBean(AnalysisIntakeCheckPort.class, () -> check)
                .run(context -> assertThat(context).hasFailed());
        enabled.withBean(AnalysisIntakeRecordDecoder.class, () -> decoder)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void pendingInvokesDurableCheckBeforeListenerReturns() {
        var change = new AnalysisIntakeChange("ref-1", "c", "PENDING");
        when(decoder.decode("project-key", "payload")).thenReturn(change);
        listener.receive(record("payload"));
        verify(check).ensureSafeToAcknowledge("ref-1");
    }

    @Test
    void staleTerminalStatusAndTombstoneDoNotCreateWork() {
        when(decoder.decode("project-key", "terminal"))
                .thenReturn(new AnalysisIntakeChange("ref-1", "u", "NOT_EVALUATED"));
        listener.receive(record("terminal"));
        listener.receive(record(null));
        verify(check, never()).ensureSafeToAcknowledge("ref-1");
        verify(decoder, never()).decode("project-key", null);
    }

    @Test
    void decoderFailureStopsDeliveryWithoutExposingPayloadOrCause() {
        when(decoder.decode("project-key", "secret-payload"))
                .thenThrow(new IllegalArgumentException("secret-payload"));
        assertThatThrownBy(() -> listener.receive(record("secret-payload")))
                .isInstanceOf(AnalysisIntakeDeliveryException.class)
                .hasMessage("CDC intake record could not be processed")
                .hasNoCause();
    }

    @Test
    void adapterFailureAndNullDecodeStopDelivery() {
        when(decoder.decode("project-key", "adapter"))
                .thenReturn(new AnalysisIntakeChange("ref-1", "c", "PENDING"));
        org.mockito.Mockito.doThrow(new IllegalStateException("sensitive db detail"))
                .when(check).ensureSafeToAcknowledge("ref-1");
        assertThatThrownBy(() -> listener.receive(record("adapter")))
                .isInstanceOf(AnalysisIntakeDeliveryException.class).hasNoCause();
        assertThatThrownBy(() -> listener.receive(record("null-decoded")))
                .isInstanceOf(AnalysisIntakeDeliveryException.class).hasNoCause();
    }

    @Test
    void receiveFailureCountsEachDeliveryAttemptWithoutAddingPayloadOrIdentifiersAsTags() {
        when(decoder.decode("project-key", "secret-payload"))
                .thenThrow(new IllegalArgumentException("secret-payload"));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> listener.receive(record("secret-payload")))
                    .isInstanceOf(AnalysisIntakeDeliveryException.class).hasNoCause();
        }
        listener.receive(record(null));
        var counter = metrics.get("beez.cdc.kafka.receive.failures").counter();
        assertThat(counter.count()).isEqualTo(2);
        assertThat(counter.getId().getTags()).isEmpty();
    }

    @Test
    void factoryDisablesAutoCommitAndKeepsSecurityValidation() {
        var properties = new CdcKafkaSecurityProperties();
        var configuration = new AnalysisIntakeKafkaConfiguration(true);
        var factory = configuration.intakeFactory("broker:9092", "group", "topic", false, properties);
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
        properties.setProperties(Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT"));
        assertThatThrownBy(() -> configuration.intakeFactory("broker:9092", "group", "topic", false, properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSimultaneousLegacyPocEvenWhenIntakeAdaptersAreAvailable() {
        contextRunner.withPropertyValues(
                "beez.cdc.intake.enabled=true", "beez.cdc.intake.consumer-enabled=true",
                "beez.cdc.legacy-outbox-poc-enabled=true",
                "beez.cdc.intake.bootstrap-servers=localhost:9092", "beez.cdc.intake.group-id=test-group",
                "beez.cdc.intake.topic=test-topic")
                .withBean(AnalysisIntakeRecordDecoder.class, () -> decoder)
                .withBean(AnalysisIntakeCheckPort.class, () -> check)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("cannot be enabled together");
                });
    }

    // 깨지면: 스캔 없이 consumer 만 ACK 해 힌트를 잃으면 분석도 잃는 구성이 기동된다(#227 규칙, 298 완료 조건 5)
    @Test
    void consumerWithoutRecoveryScanIsRejectedAtStartup() {
        contextRunner.withPropertyValues(
                "beez.cdc.intake.consumer-enabled=true", "beez.cdc.intake.bootstrap-servers=localhost:9092",
                "beez.cdc.intake.group-id=test-group", "beez.cdc.intake.topic=test-topic")
                .withBean(AnalysisIntakeRecordDecoder.class, () -> decoder)
                .withBean(AnalysisIntakeCheckPort.class, () -> check)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasStackTraceContaining("requires beez.cdc.intake.enabled");
                });
    }

    // 깨지면: Kafka 이름이 안 풀릴 때 기동이 다시 실패한다(295 리허설 재현 A: No resolvable bootstrap urls).
    // 시작 재시도도 예외를 밖으로 내지 않고, 지표는 consumer 가 멈췄음(0)을 보여야 한다
    @Test
    void unresolvableKafkaDoesNotFailStartupAndConsumerIsReportedDown() {
        contextRunner.withPropertyValues(
                "beez.cdc.intake.enabled=true", "beez.cdc.intake.consumer-enabled=true",
                "beez.cdc.intake.bootstrap-servers=kafka.invalid:9092",
                "beez.cdc.intake.group-id=test-group", "beez.cdc.intake.topic=test-topic",
                // 시험이 직접 부른다(스케줄러가 끼어들지 않게)
                "beez.cdc.intake.consumer-start-initial-delay-millis=3600000")
                .withBean(AnalysisIntakeRecordDecoder.class, () -> decoder)
                .withBean(AnalysisIntakeCheckPort.class, () -> check)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var starter = context.getBean(AnalysisIntakeConsumerStarter.class);
                    starter.ensureStarted();
                    starter.ensureStarted();
                    var container = context.getBean(KafkaListenerEndpointRegistry.class)
                            .getListenerContainer(AnalysisIntakeKafkaListener.LISTENER_ID);
                    assertThat(container).isNotNull();
                    assertThat(container.isChildRunning()).isFalse();
                    assertThat(metrics.get("beez.cdc.kafka.consumer.up").gauge().value()).isZero();
                });
    }

    @Test
    void factoryRequiresAllConsumerSettings() {
        var configuration = new AnalysisIntakeKafkaConfiguration(true);
        var security = new CdcKafkaSecurityProperties();
        assertThatThrownBy(() -> configuration.intakeFactory("", "group", "topic", false, security))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.intakeFactory("broker:9092", "", "topic", false, security))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.intakeFactory("broker:9092", "group", "", false, security))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
