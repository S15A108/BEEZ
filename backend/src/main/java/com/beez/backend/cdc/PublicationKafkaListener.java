package com.beez.backend.cdc;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "beez.cdc", name = {"enabled", "legacy-outbox-poc-enabled"}, havingValue = "true")
public class PublicationKafkaListener {
    private final PublicationEventParser parser;
    private final PublicationInboxService inbox;
    public PublicationKafkaListener(PublicationEventParser parser, PublicationInboxService inbox) {
        this.parser = parser; this.inbox = inbox;
    }

    @KafkaListener(topics = "${beez.cdc.topic:}", groupId = "${beez.cdc.group-id:}", containerFactory = "cdcKafkaListenerContainerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        try {
            parser.parse(record.value()).ifPresent(inbox::accept);
        } catch (RuntimeException ignored) {
            // Database and parsing exceptions can include raw payloads or credentials.
            // Preserve retry behavior without leaking those details to the Kafka error handler log.
            throw new CdcEventException("Publication CDC record could not be processed");
        }
    }
}
