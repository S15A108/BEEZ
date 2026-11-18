package com.beez.backend.cdc.recoveryfixture;

import static org.assertj.core.api.Assertions.assertThat;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic tables and consumer; not an implementation of #197. */
@Tag("integration")
class ConsumerRecoveryLiveIntegrationTest {
    private static final String TOPIC = "recovery.public.recovery_source";
    private static final String GROUP = "recovery-fixture-group";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void pausedConsumerAndReplaysRecoverWithoutDuplicateFixtureReceipts() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15444/recovery", "recovery", "recovery_local_only");
             var admin = AdminClient.create(Map.of("bootstrap.servers", "127.0.0.1:29095"))) {
            execute(db, "CREATE TABLE recovery_source(id INT PRIMARY KEY, project_id INT NOT NULL, version INT NOT NULL)");
            execute(db, "CREATE TABLE recovery_receipt(source_id INT PRIMARY KEY)");
            configureConnector();
            awaitSlot(db);
            insertSource(db, 1);
            try (var consumer = consumer()) {
                var events = receive(consumer, 1);
                assertThat(ids(events)).containsExactly(1);
                assertThat(offsets(events)).containsExactly(0L);
                recordReceipts(db, events);
                consumer.commitSync();
            }
            assertThat(committed(admin)).isEqualTo(1);

            // Group has no running consumer while the source continues committing.
            insertSource(db, 2);
            insertSource(db, 3);
            long end = awaitEndOffset(admin, 3);
            assertThat(end - committed(admin)).as("lag while consumer is stopped").isEqualTo(2);
            try (var restarted = consumer()) {
                var events = receive(restarted, 2);
                assertThat(ids(events)).containsExactly(2, 3);
                assertThat(offsets(events)).containsExactly(1L, 2L);
                recordReceipts(db, events);
                restarted.commitSync();
            }
            assertThat(committed(admin)).isEqualTo(3);
            assertThat(end - committed(admin)).as("lag after recovery").isZero();
            assertThat(count(db)).isEqualTo(3);

            // Rewind the stopped group's committed offset, not just an in-memory seek.
            admin.alterConsumerGroupOffsets(GROUP, Map.of(PARTITION, new OffsetAndMetadata(0))).all()
                    .get(20, TimeUnit.SECONDS);
            try (var replay = consumer()) {
                var events = receive(replay, 3);
                assertThat(ids(events)).containsExactly(1, 2, 3);
                assertThat(offsets(events)).containsExactly(0L, 1L, 2L);
                assertThat(events).allMatch(e -> e.topic().equals(TOPIC) && e.partition() == 0);
                recordReceipts(db, events);
                replay.commitSync();
            }
            assertThat(count(db)).isEqualTo(3);
            assertThat(committed(admin)).isEqualTo(3);

            // DB commit succeeds, then consumer disappears before committing offset.
            insertSource(db, 4);
            awaitEndOffset(admin, 4);
            try (var interrupted = consumer()) {
                var events = receive(interrupted, 1);
                assertThat(ids(events)).containsExactly(4);
                assertThat(offsets(events)).containsExactly(3L);
                recordReceipts(db, events);
            }
            assertThat(committed(admin)).isEqualTo(3);
            try (var restarted = consumer()) {
                var events = receive(restarted, 1);
                assertThat(ids(events)).containsExactly(4);
                assertThat(offsets(events)).containsExactly(3L);
                recordReceipts(db, events);
                restarted.commitSync();
            }
            assertThat(committed(admin)).isEqualTo(4);
            assertThat(count(db)).isEqualTo(4);
        }
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:29095");
        p.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var consumer = new KafkaConsumer<String, String>(p);
        consumer.subscribe(List.of(TOPIC));
        return consumer;
    }

    private static List<ConsumerRecord<String,String>> receive(KafkaConsumer<String,String> consumer, int expected) {
        List<ConsumerRecord<String,String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(500)).forEach(records::add);
        }
        assertThat(records).hasSize(expected);
        return records;
    }

    private static List<Integer> ids(List<ConsumerRecord<String,String>> events) {
        return events.stream().map(e -> JSON.readTree(e.value()).path("after").path("id").asInt()).toList();
    }
    private static List<Long> offsets(List<ConsumerRecord<String,String>> events) {
        return events.stream().map(ConsumerRecord::offset).toList();
    }
    private static void recordReceipts(Connection db, List<ConsumerRecord<String,String>> events) throws Exception {
        db.setAutoCommit(false);
        try (var statement = db.prepareStatement("INSERT INTO recovery_receipt(source_id) VALUES(?) ON CONFLICT DO NOTHING")) {
            for (int id : ids(events)) { statement.setInt(1, id); statement.addBatch(); }
            statement.executeBatch();
            db.commit();
        } catch (Exception e) {
            db.rollback(); throw e;
        } finally {
            db.setAutoCommit(true);
        }
    }
    private static void insertSource(Connection db, int id) throws Exception {
        try (var statement = db.prepareStatement("INSERT INTO recovery_source(id,project_id,version) VALUES(?,10,?)")) {
            statement.setInt(1, id); statement.setInt(2, id); statement.executeUpdate();
        }
    }
    private static int count(Connection db) throws Exception {
        try (var statement = db.createStatement(); var rs = statement.executeQuery("SELECT count(*) FROM recovery_receipt")) {
            rs.next(); return rs.getInt(1);
        }
    }
    private static void execute(Connection db, String sql) throws Exception {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }
    private static long committed(AdminClient admin) throws Exception {
        var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(20, TimeUnit.SECONDS);
        assertThat(offsets).containsKey(PARTITION);
        return offsets.get(PARTITION).offset();
    }
    private static long awaitEndOffset(AdminClient admin, long minimum) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        long end = -1;
        while (System.nanoTime() < deadline) {
            try {
                end = admin.listOffsets(Map.of(PARTITION, OffsetSpec.latest())).all().get(10, TimeUnit.SECONDS).get(PARTITION).offset();
                if (end >= minimum) return end;
            } catch (Exception ignored) {
                // Topic creation and broker metadata may be asynchronous.
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timed out awaiting broker end offset " + minimum + ", got " + end);
    }
    private static void awaitSlot(Connection db) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try (var statement = db.createStatement(); var rs = statement.executeQuery(
                    "SELECT count(*) FROM pg_replication_slots WHERE slot_name='recovery_test' AND active AND confirmed_flush_lsn IS NOT NULL")) {
                rs.next(); if (rs.getInt(1) == 1) return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Debezium replication slot did not become active");
    }
    private static void configureConnector() throws Exception {
        var config = Map.ofEntries(
                Map.entry("connector.class", "io.debezium.connector.postgresql.PostgresConnector"),
                Map.entry("database.hostname", "postgres"), Map.entry("database.port", "5432"),
                Map.entry("database.user", "recovery"), Map.entry("database.password", "recovery_local_only"),
                Map.entry("database.dbname", "recovery"), Map.entry("topic.prefix", "recovery"),
                Map.entry("plugin.name", "pgoutput"), Map.entry("slot.name", "recovery_test"),
                Map.entry("publication.name", "recovery_test"), Map.entry("publication.autocreate.mode", "filtered"),
                Map.entry("table.include.list", "public.recovery_source"), Map.entry("snapshot.mode", "no_data"),
                Map.entry("key.converter", "org.apache.kafka.connect.json.JsonConverter"),
                Map.entry("key.converter.schemas.enable", "false"),
                Map.entry("value.converter", "org.apache.kafka.connect.json.JsonConverter"),
                Map.entry("value.converter.schemas.enable", "false"));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18086/connectors/recovery/config"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(config))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
    }
}
