package com.beez.backend.cdc.intakefixture;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.cdc.intake.AnalysisIntakeChange;
import com.beez.backend.cdc.intake.AnalysisIntakeCheckPort;
import com.beez.backend.cdc.transport.AnalysisIntakeKafkaConfiguration;
import com.beez.backend.cdc.transport.AnalysisIntakeRecordDecoder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Real Kafka and Spring listener, with explicitly non-durable #197 test doubles. */
@Tag("integration")
@SpringJUnitConfig(IntakeConsumerLiveIntegrationTest.FixtureConfiguration.class)
class IntakeConsumerLiveIntegrationTest {
    static final String BROKER = requiredEnv("INTAKE_226_TEST_BOOTSTRAP");
    static final String TOPIC = requiredEnv("INTAKE_226_TEST_TOPIC");
    static final String GROUP = requiredEnv("INTAKE_226_TEST_GROUP");
    static final BlockingQueue<String> accepted = new LinkedBlockingQueue<>();
    static final List<String> calls = new CopyOnWriteArrayList<>();
    // Only a fixture for authoritative-state checks; not #197 persistence.
    static final Set<String> fixtureCompleted = ConcurrentHashMap.newKeySet();
    static final AtomicInteger retryAttempts = new AtomicInteger();
    static final AtomicInteger deleteDecodes = new AtomicInteger();
    static final CountDownLatch secondAttemptEntered = new CountDownLatch(1);
    static final CountDownLatch allowSecondAttempt = new CountDownLatch(1);

    @Autowired KafkaListenerEndpointRegistry registry;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("beez.cdc.intake.enabled", () -> "true");
        p.add("beez.cdc.intake.bootstrap-servers", () -> BROKER);
        p.add("beez.cdc.intake.group-id", () -> GROUP);
        p.add("beez.cdc.intake.topic", () -> TOPIC);
    }

    @Configuration(proxyBeanMethods = false)
    @Import(AnalysisIntakeKafkaConfiguration.class)
    static class FixtureConfiguration {
        @Bean AnalysisIntakeRecordDecoder fixtureDecoder() {
            return (key, value) -> {
                String[] fields = value.split("\\|", -1);
                if (fields.length != 3) throw new IllegalArgumentException("Invalid test fixture");
                if ("d".equals(fields[1])) deleteDecodes.incrementAndGet();
                return new AnalysisIntakeChange(fields[0], fields[1], fields[2]);
            };
        }

        @Bean AnalysisIntakeCheckPort fixturePort() {
            return reference -> {
                calls.add(reference);
                if ("retry".equals(reference)) {
                    int attempt = retryAttempts.incrementAndGet();
                    if (attempt == 1) throw new IllegalStateException("simulated transient fixture failure");
                    if (attempt == 2) {
                        secondAttemptEntered.countDown();
                        try {
                            if (!allowSecondAttempt.await(30, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("fixture release timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("fixture interrupted");
                        }
                    }
                }
                if (fixtureCompleted.add(reference)) accepted.add(reference);
            };
        }
    }

    @Test
    void failedDeliveryDoesNotAdvanceAndSameRecordRetriesBeforeLaterEvents() throws Exception {
        Properties producerConfig = new Properties();
        producerConfig.put("bootstrap.servers", BROKER);
        producerConfig.put("key.serializer", StringSerializer.class.getName());
        producerConfig.put("value.serializer", StringSerializer.class.getName());
        producerConfig.put("acks", "all");
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", BROKER));
             KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig)) {
            await("listener partition assignment", () -> registry.getListenerContainers().stream()
                    .anyMatch(c -> c.getAssignedPartitions() != null && c.getAssignedPartitions().contains(partition)), 30);
            var first = producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", "retry|c|PENDING")).get(15, TimeUnit.SECONDS);
            var second = producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", "later|c|PENDING")).get(15, TimeUnit.SECONDS);
            var ignored = producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", "initial|r|NOT_EVALUATED")).get(15, TimeUnit.SECONDS);
            var tombstone = producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", null)).get(15, TimeUnit.SECONDS);
            assertThat(second.offset()).isEqualTo(first.offset() + 1);
            assertThat(ignored.offset()).isEqualTo(first.offset() + 2);
            assertThat(tombstone.offset()).isEqualTo(first.offset() + 3);

            try {
                assertThat(secondAttemptEntered.await(20, TimeUnit.SECONDS)).as("same failed event retried").isTrue();
                OffsetAndMetadata before = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(10, TimeUnit.SECONDS).get(partition);
                assertThat(before == null || before.offset() <= first.offset()).as("failed record is not committed").isTrue();
                assertThat(calls).containsExactly("retry", "retry");
                assertThat(accepted).isEmpty();
            } finally {
                allowSecondAttempt.countDown();
            }

            assertThat(accepted.poll(20, TimeUnit.SECONDS)).isEqualTo("retry");
            assertThat(accepted.poll(20, TimeUnit.SECONDS)).isEqualTo("later");
            await("offset after later, NOT_EVALUATED and tombstone", () -> {
                try {
                    var committed = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                            .get(5, TimeUnit.SECONDS).get(partition);
                    return committed != null && committed.offset() >= tombstone.offset() + 1;
                } catch (Exception e) {
                    return false;
                }
            }, 30);
            assertThat(calls).containsExactly("retry", "retry", "later");
            assertThat(accepted).isEmpty();

            // Stop the actual Spring listener, wait for this exact group to be
            // empty, and publish while it is stopped. Do not use a new group.
            var container = registry.getListenerContainers().stream().findFirst().orElseThrow();
            container.stop();
            await("consumer group to leave after stop", () -> groupIsEmpty(admin), 30);
            var beforeRestart = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS).get(partition);
            assertThat(beforeRestart).isNotNull();
            assertThat(beforeRestart.offset()).isEqualTo(tombstone.offset() + 1);
            var duringStop = producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", "during-stop|c|PENDING"))
                    .get(15, TimeUnit.SECONDS);
            assertThat(duringStop.offset()).isEqualTo(beforeRestart.offset());
            assertThat(committedOffset(admin, partition)).as("stopped group has not consumed new record")
                    .isEqualTo(beforeRestart.offset());
            assertThat(calls).containsExactly("retry", "retry", "later");
            assertThat(accepted).isEmpty();
            container.start();
            await("same group reassignment", () -> container.getAssignedPartitions() != null
                    && container.getAssignedPartitions().contains(partition), 30);
            assertThat(accepted.poll(20, TimeUnit.SECONDS)).isEqualTo("during-stop");
            await("offset after restart", () -> committedOffset(admin, partition) == duringStop.offset() + 1, 30);

            // Reset only after the group has fully left, then replay the exact
            // previous offsets. Calls prove rechecking, while this in-memory
            // fixture's terminal states prevent duplicate simulated work.
            container.stop();
            await("consumer group to leave before rewind", () -> groupIsEmpty(admin), 30);
            admin.alterConsumerGroupOffsets(GROUP, Map.of(partition, new OffsetAndMetadata(first.offset())))
                    .all().get(10, TimeUnit.SECONDS);
            assertThat(committedOffset(admin, partition)).isEqualTo(first.offset());
            container.start();
            await("same group reassignment after rewind", () -> container.getAssignedPartitions() != null
                    && container.getAssignedPartitions().contains(partition), 30);
            await("replayed offsets rechecked", () -> calls.size() >= 7
                    && committedOffset(admin, partition) == duringStop.offset() + 1, 30);
            assertThat(calls).containsExactly("retry", "retry", "later", "during-stop",
                    "retry", "later", "during-stop");
            assertThat(accepted).as("fixture current state suppresses duplicate simulated work").isEmpty();

            var deletion = producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", "removed|d|")).get(15, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(TOPIC, 0, "project-1", "after-delete|c|PENDING")).get(15, TimeUnit.SECONDS);
            await("unknown deletion to retry without acknowledgement", () -> deleteDecodes.get() >= 2, 20);
            var afterDeletion = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS).get(partition);
            assertThat(afterDeletion == null || afterDeletion.offset() <= deletion.offset())
                    .as("unsupported deletion must not be committed").isTrue();
            assertThat(calls).containsExactly("retry", "retry", "later", "during-stop",
                    "retry", "later", "during-stop");
        } finally {
            allowSecondAttempt.countDown();
        }
    }

    static boolean groupIsEmpty(Admin admin) {
        try {
            var description = admin.describeConsumerGroups(List.of(GROUP)).describedGroups().get(GROUP)
                    .get(5, TimeUnit.SECONDS);
            return description.members().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static long committedOffset(Admin admin, TopicPartition partition) {
        try {
            var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1 : offset.offset();
        } catch (Exception e) {
            return -1;
        }
    }

    static void await(String label, BooleanSupplier condition, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError(label + " did not complete in time");
    }

    static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
