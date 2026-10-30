package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PublicationEventParserTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    final PublicationEventParser parser = new PublicationEventParser();

    static String envelope(UUID id, String kind, long project, long baseline, boolean stringPayload) {
        var body = new LinkedHashMap<String, Object>();
        body.put("event_id", id.toString()); body.put("event_type", "DESIGN_PUBLISHED"); body.put("event_version", 1);
        body.put("occurred_at", "2026-10-29T06:00:00Z"); body.put("publication_kind", kind);
        body.put("project_id", Long.toString(project)); body.put("baseline_id", Long.toString(baseline));
        body.put("previous_baseline_id", "INITIAL".equals(kind) ? null : "99");
        body.put("change_proposal_id", "INITIAL".equals(kind) ? null : "101");
        body.put("changed_items", "INITIAL".equals(kind) ? List.of() : List.of(Map.of("design_item_id", "10",
                "previous_version_id", "1", "published_version_id", "2", "change_type", "UPDATED")));
        var row = Map.of("event_id", id.toString(), "event_type", "DESIGN_PUBLISHED", "event_version", 1,
                "project_id", project, "baseline_id", baseline, "payload", stringPayload ? JSON.writeValueAsString(body) : body);
        return JSON.writeValueAsString(Map.of("payload", Map.of("op", "c", "after", row)));
    }

    @Test void acceptsJsonbAsObjectOrAsStringAndIgnoresTombstones() {
        UUID id = UUID.randomUUID();
        var object = parser.parse(envelope(id, "INITIAL", 1, 2, false)).orElseThrow();
        var string = parser.parse(envelope(id, "INITIAL", 1, 2, true)).orElseThrow();
        assertThat(object).isEqualTo(string);
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("{\"payload\":{\"op\":\"d\"}}" )).isEmpty();
    }

    @Test void rejectsInconsistentEventOrUnknownOperation() {
        UUID id = UUID.randomUUID();
        String valid = envelope(id, "CHANGE", 1, 2, false);
        assertThatThrownBy(() -> parser.parse(valid.replace("\"event_type\":\"DESIGN_PUBLISHED\"", "\"event_type\":\"UNKNOWN\"")))
                .isInstanceOf(CdcEventException.class);
        assertThatThrownBy(() -> parser.parse(valid.replace("\"op\":\"c\"", "\"op\":\"u\"")))
                .isInstanceOf(CdcEventException.class);
        assertThatThrownBy(() -> parser.parse(valid.replace("\"publication_kind\":\"CHANGE\"", "\"publication_kind\":\"UNKNOWN\"")))
                .isInstanceOf(CdcEventException.class);
    }

    @Test void listenerNeverSwallowsDatabaseFailure() {
        var inbox = mock(PublicationInboxService.class);
        doThrow(new IllegalStateException("db error")).when(inbox).accept(any());
        var listener = new PublicationKafkaListener(parser, inbox);
        assertThatThrownBy(() -> listener.receive(new org.apache.kafka.clients.consumer.ConsumerRecord<>("test", 0, 0L, null, envelope(UUID.randomUUID(), "CHANGE", 1, 2, false))))
                .isInstanceOf(CdcEventException.class).hasNoCause();
        listener.receive(new org.apache.kafka.clients.consumer.ConsumerRecord<>("test", 0, 1L, null, null));
        verify(inbox, times(1)).accept(any());
    }

    @Test void enablingCdcWithoutExplicitSettingsFails() {
        var config = new CdcKafkaConfig();
        assertThatThrownBy(() -> config.cdcKafkaListenerContainerFactory("", "group", "topic", new CdcKafkaSecurityProperties(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void enablingLegacyConsumerWithoutExplicitPocOptInFails() {
        var config = new CdcKafkaConfig();
        assertThatThrownBy(() -> config.cdcKafkaListenerContainerFactory("broker:9092", "group", "topic", new CdcKafkaSecurityProperties(), false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("#197 intake consumer is not implemented");
    }

    @Test void acceptsSchemaDisabledEnvelopeAndRejectsMalformedChangeVersions() {
        UUID id = UUID.randomUUID();
        String wrapped = envelope(id, "CHANGE", 1, 2, false);
        var outer = JSON.readTree(wrapped);
        String unwrapped = JSON.writeValueAsString(outer.path("payload"));
        assertThat(parser.parse(unwrapped)).isEqualTo(parser.parse(wrapped));
        assertThatThrownBy(() -> parser.parse(wrapped.replace("\"published_version_id\":\"2\"", "\"published_version_id\":null")))
                .isInstanceOf(CdcEventException.class);
        assertThatThrownBy(() -> parser.parse(wrapped.replace("\"change_proposal_id\":\"101\"", "\"change_proposal_id\":null")))
                .isInstanceOf(CdcEventException.class);
    }
}
