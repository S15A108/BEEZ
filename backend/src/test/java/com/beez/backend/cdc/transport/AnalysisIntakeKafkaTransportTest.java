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
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.CommonClientConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.listener.ContainerProperties;

class AnalysisIntakeKafkaTransportTest {
    private final AnalysisIntakeCheckPort check = mock(AnalysisIntakeCheckPort.class);
    private final AnalysisIntakeRecordDecoder decoder = mock(AnalysisIntakeRecordDecoder.class);
    private final AnalysisIntakeKafkaListener listener = new AnalysisIntakeKafkaListener(decoder,
            new AnalysisIntakeSignalHandler(check));
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AnalysisIntakeKafkaConfiguration.class);

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
                "beez.cdc.intake.enabled=true", "beez.cdc.intake.bootstrap-servers=localhost:9092",
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
    void factoryDisablesAutoCommitAndKeepsSecurityValidation() {
        var properties = new CdcKafkaSecurityProperties();
        var configuration = new AnalysisIntakeKafkaConfiguration();
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
                "beez.cdc.intake.enabled=true", "beez.cdc.legacy-outbox-poc-enabled=true",
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

    @Test
    void factoryRequiresAllConsumerSettings() {
        var configuration = new AnalysisIntakeKafkaConfiguration();
        var security = new CdcKafkaSecurityProperties();
        assertThatThrownBy(() -> configuration.intakeFactory("", "group", "topic", false, security))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.intakeFactory("broker:9092", "", "topic", false, security))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.intakeFactory("broker:9092", "group", "", false, security))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
