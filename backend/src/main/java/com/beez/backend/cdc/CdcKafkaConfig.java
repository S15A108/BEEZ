package com.beez.backend.cdc;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@EnableConfigurationProperties(CdcKafkaSecurityProperties.class)
@ConditionalOnProperty(prefix = "beez.cdc", name = "enabled", havingValue = "true")
public class CdcKafkaConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> cdcKafkaListenerContainerFactory(
            @Value("${beez.cdc.bootstrap-servers:}") String bootstrap,
            @Value("${beez.cdc.group-id:}") String groupId,
            @Value("${beez.cdc.topic:}") String topic, CdcKafkaSecurityProperties security,
            @Value("${beez.cdc.legacy-outbox-poc-enabled:false}") boolean legacyPocEnabled) {
        if (!legacyPocEnabled)
            throw new IllegalStateException("Legacy outbox CDC is disabled; #197 intake consumer is not implemented");
        if (bootstrap.isBlank() || groupId.isBlank() || topic.isBlank())
            throw new IllegalArgumentException("CDC bootstrap servers, group id and topic are required when enabled");
        Map<String, Object> config = new HashMap<>();
        config.putAll(security.validatedProperties());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Never skip malformed events or DB failures: retry indefinitely, preserving their offset.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE)));
        return factory;
    }
}
