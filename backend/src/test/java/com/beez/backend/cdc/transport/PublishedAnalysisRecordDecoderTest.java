package com.beez.backend.cdc.transport;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PublishedAnalysisRecordDecoderTest {
    private final PublishedAnalysisRecordDecoder decoder = new PublishedAnalysisRecordDecoder();
    private static final String SOURCE = "\"source\":{\"schema\":\"public\",\"table\":\"published_change_analysis\"}";

    @Test void acceptsDebeziumPlainAndSchemaWrappedEvents() {
        var plain = decoder.decode("project-1", "{" + SOURCE + ",\"op\":\"c\",\"after\":{\"id\":42,\"status\":\"PENDING\"}}");
        assertThat(plain.intakeReference()).isEqualTo("42");
        assertThat(plain.operation()).isEqualTo("c");
        assertThat(plain.observedStatus()).isEqualTo("PENDING");
        var snapshot = decoder.decode(null, "{\"schema\":{},\"payload\":{" + SOURCE
                + ",\"op\":\"r\",\"after\":{\"id\":42,\"status\":\"NOT_EVALUATED\"}}}");
        assertThat(snapshot.operation()).isEqualTo("r");
        assertThat(snapshot.observedStatus()).isEqualTo("NOT_EVALUATED");
    }

    @Test void rejectsMalformedWrongSourceAndUnrepresentableIdsWithoutEchoingPayload() {
        for (String input : new String[] {"secret", "{}", "{\"op\":\"c\",\"after\":{\"id\":1}}",
                "{" + SOURCE + ",\"op\":\"u\",\"after\":{\"id\":1.5,\"status\":\"PENDING\"}}",
                "{" + SOURCE + ",\"op\":\"u\",\"after\":{\"id\":9223372036854775808,\"status\":\"PENDING\"}}"}) {
            assertThatThrownBy(() -> decoder.decode(null, input)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid intake event").hasNoCause();
        }
    }

    @Test void deleteIsPassedToFailClosedPolicyInsteadOfBeingMistakenForATombstone() {
        var change = decoder.decode(null, "{" + SOURCE + ",\"op\":\"d\",\"before\":{\"id\":42},\"after\":null}");
        assertThatThrownBy(() -> com.beez.backend.cdc.intake.AnalysisIntakeEventPolicy.decide(change.operation(), change.observedStatus()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
