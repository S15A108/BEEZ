package com.beez.backend.cdc.transport;

import com.beez.backend.cdc.CdcKafkaSecurityProperties;
import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import com.beez.backend.cdc.intake.AnalysisIntakeSignalHandler;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Isolated opt-in Kafka transport (S15P31A108-298). It is a wake-up hint on top of the durable
 * recovery scan ({@code beez.cdc.intake.enabled}); the consumer alone is rejected (#227).
 * The listener does not start with the context: {@link AnalysisIntakeConsumerStarter} starts it
 * and retries, so a missing Kafka never fails application startup.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(CdcKafkaSecurityProperties.class)
@ConditionalOnProperty(prefix = "beez.cdc.intake", name = "consumer-enabled", havingValue = "true")
public class AnalysisIntakeKafkaConfiguration {
    /** Upper bound of the client's reconnect backoff while Kafka is unreachable; limits warning logs. */
    static final int RECONNECT_BACKOFF_MAX_MILLIS = 10_000;

    public AnalysisIntakeKafkaConfiguration(@Value("${beez.cdc.intake.enabled:false}") boolean recoveryEnabled) {
        if (!recoveryEnabled) {
            // A consumer acknowledges hints; without the recovery scan a lost hint is lost work (#227).
            throw new IllegalStateException("CDC intake consumer requires beez.cdc.intake.enabled (recovery scan)");
        }
    }

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
        // Default 1s backoff logs ~400 lines/min while Kafka is gone (295 rehearsal); reconnect may lag up to 10s.
        config.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, RECONNECT_BACKOFF_MAX_MILLIS);
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
            AnalysisIntakeRecordDecoder decoder, AnalysisIntakeCheckPort durableIntakeCheck, MeterRegistry registry) {
        return new AnalysisIntakeKafkaListener(decoder, new AnalysisIntakeSignalHandler(durableIntakeCheck), registry);
    }

    @Bean
    public AnalysisIntakeConsumerStarter intakeConsumerStarter(KafkaListenerEndpointRegistry listeners,
                                                               MeterRegistry registry) {
        return new AnalysisIntakeConsumerStarter(() -> listeners.getListenerContainer(AnalysisIntakeKafkaListener.LISTENER_ID),
                registry);
    }
}
