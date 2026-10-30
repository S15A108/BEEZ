package com.beez.backend.cdc.transport;

import com.beez.backend.cdc.CdcKafkaSecurityProperties;
import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
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

/**
 * Isolated opt-in transport. Startup requires the future schema decoder and a
 * durable intake-check adapter; no implementation is registered by this module.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableConfigurationProperties(CdcKafkaSecurityProperties.class)
@ConditionalOnProperty(prefix = "beez.cdc.intake", name = "enabled", havingValue = "true")
public class AnalysisIntakeKafkaConfiguration {
    @Bean("cdcIntakeKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> intakeFactory(
            @Value("${beez.cdc.intake.bootstrap-servers:}") String bootstrap,
            @Value("${beez.cdc.intake.group-id:}") String groupId,
            @Value("${beez.cdc.intake.topic:}") String topic,
            @Value("${beez.cdc.legacy-outbox-poc-enabled:false}") boolean legacyPocEnabled,
            CdcKafkaSecurityProperties security) {
        if (legacyPocEnabled) {
            throw new IllegalStateException("CDC intake and legacy outbox PoC cannot be enabled together");
        }
        if (bootstrap.isBlank() || groupId.isBlank() || topic.isBlank()) {
            throw new IllegalArgumentException("CDC intake bootstrap servers, group id and topic are required");
        }
        Map<String, Object> config = new HashMap<>(security.validatedProperties());
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(config));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        // Transport failure is separate from the domain's bounded retry policy.
        // Do not skip a malformed record or a failed durable intake operation.
        factory.setCommonErrorHandler(new DefaultErrorHandler(new FixedBackOff(1000L, Long.MAX_VALUE)));
        return factory;
    }

    @Bean
    public AnalysisIntakeKafkaListener intakeKafkaListener(
            AnalysisIntakeRecordDecoder decoder, AnalysisIntakeCheckPort durableIntakeCheck) {
        return new AnalysisIntakeKafkaListener(decoder, new AnalysisIntakeSignalHandler(durableIntakeCheck));
    }
}
