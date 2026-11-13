package com.beez.backend.cdc.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToDoubleFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Global DB gauges share one short-lived snapshot rather than querying once per meter. */
@Component
public class PublishedAnalysisMetrics {
    private static final Logger log = LoggerFactory.getLogger(PublishedAnalysisMetrics.class);
    private final PublishedAnalysisMetricsReader reader;
    private final boolean recoveryEnabled;
    private final boolean consumerEnabled;
    private final long cacheNanos;
    private volatile Observation observation;

    private record Observation(PublishedAnalysisMetricsSnapshot data, long capturedNanos, double lastSuccessEpochSeconds) { }

    public PublishedAnalysisMetrics(MeterRegistry registry, PublishedAnalysisMetricsReader reader,
                                    @Value("${beez.cdc.intake.enabled:false}") boolean recoveryEnabled,
                                    @Value("${beez.cdc.intake.consumer-enabled:false}") boolean consumerEnabled,
                                    @Value("${beez.cdc.observability.cache-millis:15000}") long cacheMillis) {
        this.reader = reader;
        this.recoveryEnabled = recoveryEnabled;
        this.consumerEnabled = consumerEnabled;
        // A zero or negative value must not turn each meter into a separate database query.
        this.cacheNanos = TimeUnit.MILLISECONDS.toNanos(Math.min(300000, Math.max(1000, cacheMillis)));
        for (String status : List.of("PENDING", "RUNNING", "COMPLETED", "FAILED", "NOT_EVALUATED")) {
            Gauge.builder("beez.cdc.analysis.intakes", this, m -> m.value(s -> s.intakes().get(status)))
                    .description("Current retained publication intake rows by processing status")
                    .tag("status", status).register(registry);
        }
        for (String result : List.of("COMPLETED", "INSUFFICIENT_EVIDENCE", "UNKNOWN")) {
            Gauge.builder("beez.cdc.analysis.evaluations", this, m -> m.value(s -> s.evaluations().get(result)))
                    .description("Completed rows by evaluation; UNKNOWN means an absent or incompatible evaluation")
                    .tag("result", result).register(registry);
        }
        for (String result : List.of("COMPLETED", "INSUFFICIENT_EVIDENCE", "NOT_EVALUATED", "FAILED")) {
            Gauge.builder("beez.cdc.analysis.attempt.records", this, m -> m.value(s -> s.attempts().get(result)))
                    .description("Retained finished attempt records by result, not a lifetime event counter")
                    .tag("result", result).register(registry);
        }
        for (String kind : PublishedAnalysisMetricsSnapshot.UNRESOLVED_KINDS) {
            for (String scope : PublishedAnalysisMetricsSnapshot.UNRESOLVED_SCOPES) {
                String key = PublishedAnalysisMetricsSnapshot.unresolvedKey(kind, scope);
                Gauge.builder("beez.cdc.analysis.unresolved", this, m -> m.value(s -> s.unresolved().get(key)))
                        .description("FAILED and review-required rows; ACTIONABLE means the intake baseline is still current")
                        .tag("kind", kind).tag("scope", scope).register(registry);
            }
        }
        gauge(registry, "beez.cdc.analysis.not.evaluated.expected", "Initial v1 and explicitly marked historical backfill rows", PublishedAnalysisMetricsSnapshot::notEvaluatedExpected);
        gauge(registry, "beez.cdc.analysis.not.evaluated.requires.review", "Other not-evaluated rows", PublishedAnalysisMetricsSnapshot::notEvaluatedRequiresReview);
        gauge(registry, "beez.cdc.analysis.blocked.projects", "Projects with pending work blocked by predecessor order", PublishedAnalysisMetricsSnapshot::blockedProjects);
        gauge(registry, "beez.cdc.analysis.pending.retries", "Pending rows with a scheduled or due retry", PublishedAnalysisMetricsSnapshot::pendingRetries);
        gauge(registry, "beez.cdc.analysis.expired.leases", "Running rows whose execution lease has expired", PublishedAnalysisMetricsSnapshot::expiredLeases);
        gauge(registry, "beez.cdc.analysis.started.retries", "Retry claims on retained intake rows", PublishedAnalysisMetricsSnapshot::startedRetries);
        gauge(registry, "beez.cdc.analysis.recorded.retry.attempts", "Retained finished attempt records after the first attempt", PublishedAnalysisMetricsSnapshot::recordedRetryAttempts);
        gauge(registry, "beez.cdc.analysis.oldest.pending.seconds", "Age of oldest pending intake at the snapshot time", PublishedAnalysisMetricsSnapshot::oldestPendingSeconds);
        Gauge.builder("beez.cdc.analysis.snapshot.available", this, m -> m.current().data() == null ? 0 : 1)
                .description("Whether the latest database snapshot succeeded").register(registry);
        Gauge.builder("beez.cdc.analysis.snapshot.last.success.epoch.seconds", this, m -> m.current().lastSuccessEpochSeconds())
                .description("Unix time of the most recent successful database snapshot").register(registry);
        Gauge.builder("beez.cdc.analysis.recovery.enabled", this, m -> m.recoveryEnabled ? 1 : 0)
                .description("Recovery scan configuration flag (analysis intake processing)").register(registry);
        Gauge.builder("beez.cdc.analysis.consumer.enabled", this, m -> m.consumerEnabled ? 1 : 0)
                .description("Kafka consumer configuration flag; see beez.cdc.kafka.consumer.up for health").register(registry);
    }

    private void gauge(MeterRegistry registry, String name, String description,
                       ToDoubleFunction<PublishedAnalysisMetricsSnapshot> read) {
        Gauge.builder(name, this, m -> m.value(read)).description(description).register(registry);
    }

    private double value(ToDoubleFunction<PublishedAnalysisMetricsSnapshot> read) {
        var snapshot = current().data();
        return snapshot == null ? Double.NaN : read.applyAsDouble(snapshot);
    }

    private Observation current() {
        var existing = observation;
        if (existing != null && System.nanoTime() - existing.capturedNanos() < cacheNanos) return existing;
        synchronized (this) {
            existing = observation;
            if (existing != null && System.nanoTime() - existing.capturedNanos() < cacheNanos) return existing;
            double lastSuccess = existing == null ? Double.NaN : existing.lastSuccessEpochSeconds();
            try {
                var snapshot = reader.read();
                observation = new Observation(snapshot, System.nanoTime(), Instant.now().toEpochMilli() / 1000.0);
            } catch (RuntimeException failure) {
                // Never log DB exception text, SQL parameters, project IDs, or document content.
                log.warn("CDC analysis metrics snapshot unavailable; it will be retried");
                observation = new Observation(null, System.nanoTime(), lastSuccess);
            }
            return observation;
        }
    }

    /** Used by tests; the next observation performs a fresh read. */
    synchronized void invalidate() {
        if (observation != null) {
            observation = new Observation(observation.data(), System.nanoTime() - cacheNanos - 1,
                    observation.lastSuccessEpochSeconds());
        }
    }
}
