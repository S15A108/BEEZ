package com.beez.backend.cdc.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;

class AnalysisIntakeConsumerStarterTest {
    private final MessageListenerContainer container = mock(MessageListenerContainer.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AnalysisIntakeConsumerStarter starter = new AnalysisIntakeConsumerStarter(() -> container, registry);

    // 깨지면: Kafka 가 돌아와도 consumer 가 다시 붙지 않는다. spring-kafka 의 Concurrent 컨테이너는 자식 시작이
    // 실패해도 isRunning()=true 로 남아(실측) start() 만 부르면 아무 일도 안 하므로, stop 뒤 start 해야 한다
    @Test
    void failedStartIsRetriedByStoppingTheHalfStartedContainerFirst() {
        when(container.isChildRunning()).thenReturn(false);
        when(container.isRunning()).thenReturn(true);
        doThrow(new IllegalStateException("No resolvable bootstrap urls")).doNothing().when(container).start();

        starter.ensureStarted();   // 실패해도 예외를 내지 않는다
        starter.ensureStarted();   // 다음 주기에 다시 시도

        var order = inOrder(container);
        order.verify(container).stop();
        order.verify(container).start();
        order.verify(container).stop();
        order.verify(container).start();
    }

    @Test
    void runningConsumerIsLeftAlone() {
        when(container.isChildRunning()).thenReturn(true);
        starter.ensureStarted();
        verify(container, never()).stop();
        verify(container, never()).start();
    }

    // 깨지면: 기동 뒤 Kafka 가 멈춘 상태(295 리허설 C — 할당은 남고 기존 지표 변화 없음)를 up 지표가 놓친다
    @Test
    void upRequiresAssignmentAndRecentHeartbeat() {
        when(container.isChildRunning()).thenReturn(true);
        when(container.getAssignedPartitions()).thenReturn(List.of(new TopicPartition("t", 0)));
        Metric heartbeat = mock(Metric.class);
        MetricName name = new MetricName("last-heartbeat-seconds-ago", "consumer-coordinator-metrics", "", Map.of());
        Map<MetricName, Metric> clientMetrics = Map.of(name, heartbeat);
        when(container.metrics()).thenAnswer(call -> Map.of("client-0", clientMetrics));
        var up = registry.get("beez.cdc.kafka.consumer.up").gauge();

        when(heartbeat.metricValue()).thenReturn(2.0);
        assertThat(up.value()).isEqualTo(1);
        when(heartbeat.metricValue()).thenReturn(AnalysisIntakeConsumerStarter.HEARTBEAT_STALE_SECONDS + 1);
        assertThat(up.value()).isZero();
        when(heartbeat.metricValue()).thenReturn(-1.0);   // 아직 그룹에 붙은 적 없음
        assertThat(up.value()).isZero();

        when(heartbeat.metricValue()).thenReturn(2.0);
        when(container.getAssignedPartitions()).thenReturn(List.of());
        assertThat(up.value()).isZero();
    }
}
