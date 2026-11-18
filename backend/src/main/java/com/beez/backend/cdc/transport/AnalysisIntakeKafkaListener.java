package com.beez.backend.cdc.transport;

import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/** Listener for the #197 intake contract. Disabled unless explicitly configured. */
public final class AnalysisIntakeKafkaListener {
    /** Container id; {@link AnalysisIntakeConsumerStarter} starts it instead of the context (autoStartup=false). */
    public static final String LISTENER_ID = "cdc-intake";
    private final AnalysisIntakeRecordDecoder decoder;
    private final AnalysisIntakeSignalHandler handler;
    private final Counter receiveFailures;

    public AnalysisIntakeKafkaListener(AnalysisIntakeRecordDecoder decoder, AnalysisIntakeSignalHandler handler) {
        this(decoder, handler, null);
    }

    public AnalysisIntakeKafkaListener(AnalysisIntakeRecordDecoder decoder, AnalysisIntakeSignalHandler handler,
                                       MeterRegistry registry) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.receiveFailures = registry == null ? null : Counter.builder("beez.cdc.kafka.receive.failures")
                .description("Failed Kafka record delivery attempts including repeated retries")
                .register(registry);
    }

    @KafkaListener(id = LISTENER_ID, autoStartup = "false",
            topics = "${beez.cdc.intake.topic:}", groupId = "${beez.cdc.intake.group-id:}",
            containerFactory = "cdcIntakeKafkaListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        try {
            Objects.requireNonNull(record, "record");
            if (record.value() == null) {
                handler.handleTombstone();
            } else {
                // The event is only a hint. The injected port must enforce the
                // current authoritative state and acknowledgement contract.
                handler.handle(Objects.requireNonNull(decoder.decode(record.key(), record.value()), "decoded change"));
            }
        } catch (RuntimeException failure) {
            // Provider and database exceptions can contain raw payloads or credentials.
            // Do not leak them into Kafka listener/error handler logs.
            if (receiveFailures != null) {
                try { receiveFailures.increment(); }
                catch (RuntimeException ignored) { /* Metrics must not change delivery behavior. */ }
            }
            throw new AnalysisIntakeDeliveryException();
        }
    }
}
