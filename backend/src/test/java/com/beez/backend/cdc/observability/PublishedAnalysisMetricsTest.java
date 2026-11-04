package com.beez.backend.cdc.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

class PublishedAnalysisMetricsTest {
    private static PublishedAnalysisMetricsSnapshot snapshot(double pending, double failedAttempts, double retries) {
        return new PublishedAnalysisMetricsSnapshot(
                Map.of("PENDING", pending, "RUNNING", 1d, "COMPLETED", 2d, "FAILED", 1d, "NOT_EVALUATED", 2d),
                Map.of("COMPLETED", 0d, "INSUFFICIENT_EVIDENCE", 2d, "UNKNOWN", 0d),
                Map.of("COMPLETED", 0d, "INSUFFICIENT_EVIDENCE", 2d, "NOT_EVALUATED", 1d, "FAILED", failedAttempts),
                1d, 1d, 1d, 1d, 0d, retries, retries, 140d);
    }

    @Test
    void aSharedSnapshotChangesWithStoredStateAndPreservesEvaluationDistinctions() {
        var reader = mock(PublishedAnalysisMetricsReader.class);
        when(reader.read()).thenReturn(snapshot(3, 1, 0), snapshot(1, 2, 1));
        var registry = new SimpleMeterRegistry();
        var metrics = new PublishedAnalysisMetrics(registry, reader, true, 15000);

        assertThat(gauge(registry, "beez.cdc.analysis.intakes", "status", "PENDING")).isEqualTo(3);
        assertThat(gauge(registry, "beez.cdc.analysis.evaluations", "result", "INSUFFICIENT_EVIDENCE")).isEqualTo(2);
        assertThat(gauge(registry, "beez.cdc.analysis.attempt.records", "result", "FAILED")).isEqualTo(1);
        assertThat(registry.get("beez.cdc.analysis.snapshot.available").gauge().value()).isEqualTo(1);
        verify(reader, times(1)).read();

        metrics.invalidate();
        assertThat(gauge(registry, "beez.cdc.analysis.intakes", "status", "PENDING")).isEqualTo(1);
        assertThat(gauge(registry, "beez.cdc.analysis.attempt.records", "result", "FAILED")).isEqualTo(2);
        assertThat(registry.get("beez.cdc.analysis.started.retries").gauge().value()).isEqualTo(1);
        verify(reader, times(2)).read();

        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey()).isIn("status", "result")));
    }

    @Test
    void databaseFailureIsNotReportedAsZeroAndCanRecover() {
        var reader = mock(PublishedAnalysisMetricsReader.class);
        when(reader.read()).thenReturn(snapshot(3, 1, 0))
                .thenThrow(new IllegalStateException("secret database data"))
                .thenReturn(snapshot(2, 1, 0));
        var registry = new SimpleMeterRegistry();
        var metrics = new PublishedAnalysisMetrics(registry, reader, false, 15000);
        Gauge available = registry.get("beez.cdc.analysis.snapshot.available").gauge();
        Gauge lastSuccess = registry.get("beez.cdc.analysis.snapshot.last.success.epoch.seconds").gauge();

        assertThat(available.value()).isEqualTo(1);
        double lastTime = lastSuccess.value();
        assertThat(lastTime).isPositive();
        metrics.invalidate();
        assertThat(available.value()).isZero();
        assertThat(gauge(registry, "beez.cdc.analysis.intakes", "status", "PENDING")).isNaN();
        assertThat(lastSuccess.value()).isEqualTo(lastTime);
        assertThat(registry.get("beez.cdc.analysis.consumer.enabled").gauge().value()).isZero();
        metrics.invalidate();
        assertThat(available.value()).isEqualTo(1);
        assertThat(gauge(registry, "beez.cdc.analysis.intakes", "status", "PENDING")).isEqualTo(2);
    }

    @Test
    void prometheusRegistryExposesFixedLabelsAndUnavailableInsteadOfZeroWhenPresent() throws Exception {
        // The Prometheus registry is owned by #244. Keep this test runnable after that dependency lands.
        Class<?> registryType;
        try { registryType = Class.forName("io.micrometer.prometheusmetrics.PrometheusMeterRegistry"); }
        catch (ClassNotFoundException missing) {
            Assumptions.assumeTrue(false, "Prometheus registry is provided by the #244 integration");
            return;
        }
        var configType = Class.forName("io.micrometer.prometheusmetrics.PrometheusConfig");
        var registry = (MeterRegistry) registryType.getConstructor(configType)
                .newInstance(configType.getField("DEFAULT").get(null));
        var reader = mock(PublishedAnalysisMetricsReader.class);
        when(reader.read()).thenReturn(snapshot(3, 1, 0)).thenThrow(new IllegalStateException("secret"));
        var metrics = new PublishedAnalysisMetrics(registry, reader, true, 15000);
        String scrape = (String) registryType.getMethod("scrape").invoke(registry);
        assertThat(scrape).contains("beez_cdc_analysis_intakes{status=\"PENDING\"} 3.0")
                .contains("beez_cdc_analysis_evaluations{result=\"INSUFFICIENT_EVIDENCE\"} 2.0")
                .doesNotContain("project_id", "baseline_id", "secret");
        metrics.invalidate();
        String unavailable = (String) registryType.getMethod("scrape").invoke(registry);
        assertThat(unavailable).contains("beez_cdc_analysis_snapshot_available 0.0")
                .contains("beez_cdc_analysis_intakes{status=\"PENDING\"} NaN").doesNotContain("secret");
        registry.close();
    }

    private static double gauge(SimpleMeterRegistry registry, String name, String key, String value) {
        return registry.get(name).tag(key, value).gauge().value();
    }
}
