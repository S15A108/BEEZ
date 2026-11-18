package com.beez.backend.cdc.transport;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Starts the intake listener outside context startup and keeps retrying (S15P31A108-298).
 *
 * <p>Creating a Kafka consumer fails synchronously when no bootstrap address resolves
 * ({@code No resolvable bootstrap urls}); inside the context's lifecycle start that failed the
 * whole application. Once a consumer exists, kafka-clients itself reconnects after a broker outage.
 *
 * <p>Trap (spring-kafka 4.1): {@code ConcurrentMessageListenerContainer} marks itself running
 * before its child container starts, so after a failed start {@code isRunning()} stays true and a
 * plain {@code start()} is a no-op. Health is judged by {@code isChildRunning()}, and a broken
 * container is stopped before it is started again.
 */
public final class AnalysisIntakeConsumerStarter {
    private static final Logger log = LoggerFactory.getLogger(AnalysisIntakeConsumerStarter.class);
    /** Heartbeats are sent every 3s by default; no heartbeat this long means no coordinator/broker. */
    static final double HEARTBEAT_STALE_SECONDS = 30;
    private static final String HEARTBEAT_METRIC = "last-heartbeat-seconds-ago";

    private final Supplier<MessageListenerContainer> container;
    private volatile boolean failing;

    public AnalysisIntakeConsumerStarter(Supplier<MessageListenerContainer> container, MeterRegistry registry) {
        this.container = container;
        Gauge.builder("beez.cdc.kafka.consumer.up", this, AnalysisIntakeConsumerStarter::up)
                .description("1 when the intake consumer runs, has partitions and a recent group heartbeat")
                .register(registry);
    }

    @Scheduled(initialDelayString = "${beez.cdc.intake.consumer-start-initial-delay-millis:0}",
            fixedDelayString = "${beez.cdc.intake.consumer-start-retry-millis:30000}")
    public void ensureStarted() {
        var listener = container.get();
        if (listener == null || listener.isChildRunning()) return;
        try {
            if (listener.isRunning()) listener.stop();
            listener.start();
            if (failing) log.info("CDC intake Kafka consumer started after earlier failures");
            failing = false;
        } catch (RuntimeException failure) {
            // Client exceptions can carry addresses or configuration; log the state change only once.
            if (!failing) log.warn("CDC intake Kafka consumer could not start; the recovery scan continues and start is retried");
            failing = true;
        }
    }

    double up() {
        try {
            var listener = container.get();
            if (listener == null || !listener.isChildRunning()) return 0;
            Collection<?> assigned = listener.getAssignedPartitions();
            if (assigned == null || assigned.isEmpty()) return 0;
            return heartbeatFresh(listener.metrics()) ? 1 : 0;
        } catch (RuntimeException failure) {
            return 0;
        }
    }

    private static boolean heartbeatFresh(Map<String, Map<MetricName, ? extends Metric>> clients) {
        if (clients == null || clients.isEmpty()) return false;
        for (var metrics : clients.values()) {
            boolean fresh = false;
            for (var entry : metrics.entrySet()) {
                if (!HEARTBEAT_METRIC.equals(entry.getKey().name())) continue;
                // -1 means no heartbeat has been sent yet.
                if (entry.getValue().metricValue() instanceof Number age) {
                    double seconds = age.doubleValue();
                    fresh = seconds >= 0 && seconds <= HEARTBEAT_STALE_SECONDS;
                }
            }
            if (!fresh) return false;
        }
        return true;
    }
}
