package com.beez.backend.cdc.transport;

import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/** Listener for the #197 intake contract. Disabled unless explicitly configured. */
public final class AnalysisIntakeKafkaListener {
    private final AnalysisIntakeRecordDecoder decoder;
    private final AnalysisIntakeSignalHandler handler;

    public AnalysisIntakeKafkaListener(AnalysisIntakeRecordDecoder decoder, AnalysisIntakeSignalHandler handler) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    @KafkaListener(topics = "${beez.cdc.intake.topic:}", groupId = "${beez.cdc.intake.group-id:}",
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
            throw new AnalysisIntakeDeliveryException();
        }
    }
}
