package com.beez.backend.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class CdcKafkaSecurityPropertiesTest {
    private final CdcKafkaConfig config = new CdcKafkaConfig();

    private CdcKafkaSecurityProperties properties(Map<String, String> values) {
        var security = new CdcKafkaSecurityProperties();
        security.setProperties(values);
        return security;
    }

    @Test
    void existingLocalConfigurationRetainsDeliverySettings() {
        var factory = config.cdcKafkaListenerContainerFactory("broker:9092", "group", "topic", properties(Map.of()), true);
        var actual = factory.getConsumerFactory().getConfigurationProperties();
        assertThat(actual).doesNotContainKey(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        assertThat(actual).containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
                .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    }

    @Test
    void passesTlsAndSaslPropertiesToConsumerWithoutChangingDeliverySettings() {
        String jaas = "example jaas secret";
        var security = properties(Map.of(
                CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL",
                SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, "/run/secrets/truststore.p12",
                SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, "secret",
                SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-512",
                SaslConfigs.SASL_JAAS_CONFIG, jaas));
        var actual = config.cdcKafkaListenerContainerFactory("broker:9092", "group", "topic", security, true)
                .getConsumerFactory().getConfigurationProperties();
        assertThat(actual).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                .containsEntry(SaslConfigs.SASL_JAAS_CONFIG, jaas)
                .containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    }

    @Test
    void bindsKafkaPropertyNamesWithDots() {
        var source = new MapConfigurationPropertySource(Map.of(
                "beez.cdc.kafka-security.properties[security.protocol]", "SASL_SSL",
                "beez.cdc.kafka-security.properties[sasl.mechanism]", "PLAIN",
                "beez.cdc.kafka-security.properties[sasl.jaas.config]", "example jaas secret"));
        var bound = new Binder(source).bind("beez.cdc.kafka-security", Bindable.of(CdcKafkaSecurityProperties.class)).get();
        assertThat(bound.validatedProperties()).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                .containsEntry(SaslConfigs.SASL_MECHANISM, "PLAIN");
    }
    @Test
    void sslCanUseJvmTrustRoots() {
        assertThat(properties(Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")).validatedProperties())
                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
    }

    @Test
    void rejectsInsecureOrIncompleteSettingsAndOverrides() {
        for (var values : java.util.List.of(
                Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT"),
                Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "PLAINTEXT"),
                Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL"),
                Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL", SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, ""),
                Map.of(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL", SaslConfigs.SASL_MECHANISM, "PLAIN"),
                Map.of(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true"))) {
            assertThatThrownBy(() -> properties(values).validatedProperties()).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
