package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos.*;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.task.TaskApiSupport;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Actual publication services, intake table, same PostgreSQL, Debezium and production listener.
 * AI is a deterministic double; provider accuracy/exactly-once external execution is not claimed. */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "BEEZ_229_TEST_SOURCE", matches = ".+")
class PublishedAnalysisCapture229IntegrationTest {
    private static final String BROKER = "127.0.0.1:29229";
    private static final String TOPIC = "recovery229.public.published_change_analysis";
    private static final String GROUP = "recovery229-application";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Map<String, Object>> EVENTS = new ArrayList<>();

    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:15329/recovery229");
        p.add("spring.datasource.username", () -> "recovery229");
        p.add("spring.datasource.password", () -> "recovery229_local_only");
        p.add("beez.cdc.intake.enabled", () -> "true");
        p.add("beez.cdc.intake.consumer-enabled", () -> "true");
        p.add("beez.cdc.intake.bootstrap-servers", () -> BROKER);
        p.add("beez.cdc.intake.topic", () -> TOPIC);
        p.add("beez.cdc.intake.group-id", () -> GROUP);
        // The initial empty scan happens during context startup. Later scans are delayed so
        // successful processing in this test must be triggered by actual captured Kafka rows.
        p.add("beez.cdc.intake.recovery-poll-millis", () -> "3600000");
        // Manual stop/restart scenarios must remain stopped until this test explicitly restarts.
        p.add("beez.cdc.intake.consumer-start-retry-millis", () -> "3600000");
    }

    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AiClient ai;
    private TaskApiSupport api;

    @Test void snapshotStreamingConsumerRestartSameOffsetReplayAndRollbackUseDurableIntakes() throws Exception {
        api = new TaskApiSupport(() -> ApiClient.forPort(Integer.parseInt(environment.getRequiredProperty("local.server.port"))), dataSource);
        api.resetAndLoadFixture();
        long base = number("SELECT current_baseline_id FROM project WHERE id=" + api.projectId());
        api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status,finished_at) "
                + "VALUES(:p,:b,'NOT_EVALUATED',clock_timestamp())")
                .param("p", api.projectId()).param("b", base).update();
        when(ai.changeImpact(any())).thenReturn(Map.of("result", Map.of("candidates", List.of(), "evidence_status", "SUFFICIENT"),
                "usage", List.of(Map.of("request_key", "cdc229", "feature", "CHANGE_IMPACT", "failed", false))));

        try (var admin = Admin.create(Map.of("bootstrap.servers", BROKER));
             var observer = new KafkaConsumer<String, String>(Map.of(
                     "bootstrap.servers", BROKER, "group.id", "recovery229-observer",
                     "enable.auto.commit", "false", "auto.offset.reset", "earliest",
                     "key.deserializer", StringDeserializer.class.getName(),
                     "value.deserializer", StringDeserializer.class.getName()))) {
            try {
                admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException alreadyCreated) {
                // The real listener can concurrently auto-create the topic while assigning.
                if (!(alreadyCreated.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException)) throw alreadyCreated;
            }
            observer.assign(List.of(PARTITION));
            observer.seekToBeginning(List.of(PARTITION));
            await(() -> listeners.getListenerContainers().stream().anyMatch(c -> c.getAssignedPartitions() != null
                    && c.getAssignedPartitions().contains(PARTITION)), 45, "application assignment");

            long snapshotId = publish("snapshot");
            assertThat(state(snapshotId)).isEqualTo("PENDING");
            registerProductionConnector();
            await(() -> "COMPLETED".equals(state(snapshotId)), 90, "initial snapshot analysis");
            collectUntil(observer, snapshotId, "r");
            assertOneAttempt(snapshotId);
            assertThat(number("SELECT count(*) FROM pg_replication_slots WHERE slot_name='recovery229_intake' AND active AND confirmed_flush_lsn IS NOT NULL")).isEqualTo(1);
            System.out.println("CDC229 snapshot=PASS real_table=published_change_analysis same_database=true");

            long streamedId = publish("streaming");
            await(() -> "COMPLETED".equals(state(streamedId)), 30, "streaming publication analysis");
            collectUntil(observer, streamedId, "c");
            assertOneAttempt(streamedId);
            System.out.println("CDC229 publication_commit_debezium_kafka_dispatch_result=PASS");
            String fixtureExport = System.getenv("BEEZ_229_EXPORT_FIXTURE");
            if (fixtureExport != null && !fixtureExport.isBlank()) exportSyntheticFixture(Path.of(fixtureExport));

            listeners.stop();
            awaitEmptyGroup(admin);
            long stoppedId = publish("consumer-stopped");
            collectUntil(observer, stoppedId, "c");
            long endWhileStopped = observer.endOffsets(List.of(PARTITION)).get(PARTITION);
            long committedBeforeRestart = committed(admin);
            assertThat(endWhileStopped).isGreaterThan(committedBeforeRestart);
            assertThat(state(stoppedId)).isEqualTo("PENDING");
            assertThat(attempts(stoppedId)).isZero();
            listeners.start();
            await(() -> "COMPLETED".equals(state(stoppedId)), 45, "same group restart");
            assertOneAttempt(stoppedId);
            awaitOffset(admin, endWhileStopped);
            System.out.println("CDC229 consumer_stop_publish_restart=PASS positive_lag=" + (endWhileStopped - committedBeforeRestart));

            long replayEnd = observer.endOffsets(List.of(PARTITION)).get(PARTITION);
            awaitOffset(admin, replayEnd);
            listeners.stop();
            awaitEmptyGroup(admin);
            admin.alterConsumerGroupOffsets(GROUP, Map.of(PARTITION, new OffsetAndMetadata(0))).all().get(15, TimeUnit.SECONDS);
            assertThat(committed(admin)).isZero();
            listeners.start();
            awaitOffset(admin, replayEnd);
            for (long id : List.of(snapshotId, streamedId, stoppedId)) assertOneAttempt(id);
            verify(ai, times(3)).changeImpact(any());
            System.out.println("CDC229 same_group_same_topic_partition_offsets_replayed=PASS attempts=3 ai_calls=3");

            long rollbackId = new TransactionTemplate(transactions).execute(status -> {
                long id = publish("rollback");
                status.setRollbackOnly();
                return id;
            });
            assertThat(number("SELECT count(*) FROM published_change_analysis WHERE id=" + rollbackId)).isZero();
            long sentinelId = publish("after-rollback");
            await(() -> "COMPLETED".equals(state(sentinelId)), 30, "post-rollback sentinel");
            collectUntil(observer, sentinelId, "c");
            assertThat(EVENTS.stream().noneMatch(e -> eventId(e) == rollbackId)).isTrue();
            assertThat(number("SELECT count(*) FROM published_change_analysis WHERE project_id=" + api.projectId())).isEqualTo(5);
            assertThat(number("SELECT count(*) FROM published_analysis_attempt")).isEqualTo(4);
            verify(ai, times(4)).changeImpact(any());
            assertThat(number("SELECT count(*) FROM cdc_publication_inbox")).isZero();
            System.out.println("CDC229 rollback_no_capture_no_intake=PASS legacy_inbox_rows=0");

            assertThat(EVENTS).isNotEmpty();
            for (var event : EVENTS) {
                Object after = event.get("after");
                if (after instanceof Map<?, ?> row) assertThat(new HashSet<>(row.keySet())).isEqualTo(Set.of("id", "status"));
            }
            System.out.println("CDC229 capture_payload_allowlist=PASS fields=id,status sampled_events=" + EVENTS.size());
            long crashId = verifyActualWorkerKillAndLeaseRecovery();
            if ("true".equals(System.getenv("BEEZ_229_UI"))) verifyLiveUi(crashId);
        }
    }

    private void exportSyntheticFixture(Path target) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Files.createDirectories(target.toAbsolutePath().getParent());
        var dump = new ProcessBuilder("docker", "compose", "-p", "beez-cdc-recovery-229-test", "-f",
                root.resolve("infra/cdc-poc/recovery-229/compose.yaml").toString(), "exec", "-T", "postgres",
                "pg_dump", "-U", "recovery229", "-d", "recovery229", "--data-only", "--column-inserts", "--no-owner", "--no-privileges",
                "--exclude-table=public.flyway_schema_history", "--exclude-table=public.spring_session*")
                .redirectOutput(target.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(dump.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(dump.exitValue()).isZero();
        } finally { stopOwnedProcess(dump); }
        System.out.println("CDC229 isolated_synthetic_fixture_export=PASS");
    }

    private long verifyActualWorkerKillAndLeaseRecovery() throws Exception {
        // Stop only this test context's listener. Its hourly recovery scan remains idle.
        listeners.stop();
        long id = publish("process-kill");
        var called = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var httpWorkers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(httpWorkers);
        server.createContext("/v1/impact", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.incrementAndGet() == 1) {
                called.countDown();
                try { release.await(30, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] response = JSON.writeValueAsString(Map.of("result", Map.of("candidates", List.of(), "evidence_status", "SUFFICIENT"),
                    "usage", List.of(Map.of("request_key", "worker-kill-229", "feature", "CHANGE_IMPACT", "failed", false)),
                    "origin", "MODEL", "model", "isolated-http-fixture")).getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (java.io.IOException killedClient) { /* The first process was forcibly terminated. */ }
            finally { exchange.close(); }
        });
        server.start();
        Process first = null, restarted = null;
        Path firstLog = Files.createTempFile("beez-229-killed-worker-", ".log");
        Path secondLog = Files.createTempFile("beez-229-restarted-worker-", ".log");
        try {
            first = startWorker(server.getAddress().getPort(), firstLog);
            assertThat(called.await(90, TimeUnit.SECONDS)).as("first real worker reached blocked HTTP AI call").isTrue();
            assertThat(state(id)).isEqualTo("RUNNING");
            assertThat(number("SELECT attempt FROM published_change_analysis WHERE id=" + id)).isEqualTo(1);
            var lease = api.jdbc.sql("SELECT lease_until FROM published_change_analysis WHERE id=:id")
                    .param("id", id).query(java.time.OffsetDateTime.class).single();
            first.destroyForcibly();
            assertThat(first.waitFor(10, TimeUnit.SECONDS)).as("forcibly terminated worker process").isTrue();
            assertThat(state(id)).isEqualTo("RUNNING");
            assertThat(attempts(id)).isZero();
            release.countDown();
            // No SQL clock/lease edits: the application chooses a real 66-second lease
            // from the test HTTP timeouts (5s read + 1s connect + 60s safety).
            restarted = startWorker(server.getAddress().getPort(), secondLog);
            await(() -> "COMPLETED".equals(state(id)), 120, "restarted actual process reclaims expired lease");
            assertThat(java.time.OffsetDateTime.now()).isAfter(lease);
            assertThat(number("SELECT attempt FROM published_change_analysis WHERE id=" + id)).isEqualTo(2);
            assertThat(attempts(id)).isEqualTo(1);
            assertThat(number("SELECT attempt FROM published_analysis_attempt WHERE intake_id=" + id)).isEqualTo(2);
            assertThat(calls.get()).isEqualTo(2);
            System.out.println("CDC229 actual_worker_forced_termination_restart=PASS natural_lease_expiry=true claim_attempt=2 persisted_result_attempt=2 http_calls=2");
            return id;
        } finally {
            release.countDown();
            stopOwnedProcess(first);
            stopOwnedProcess(restarted);
            server.stop(0);
            httpWorkers.close();
            // Logs contain only isolated synthetic data, retained in the OS temp directory on
            // failure for debugging, never included in repository evidence.
        }
    }

    private Process startWorker(int aiPort, Path log) throws Exception {
        Path backend = Path.of(".").toAbsolutePath().normalize();
        Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        return new ProcessBuilder(java.toString(), "-Xmx384m", "-jar", backend.resolve("build/libs/beez-backend-0.0.1-SNAPSHOT.jar").toString(),
                "--spring.profiles.active=qa229", "--server.address=127.0.0.1", "--server.port=0", "--spring.docker.compose.enabled=false",
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:15329/recovery229", "--spring.datasource.username=recovery229",
                "--spring.datasource.password=recovery229_local_only", "--spring.datasource.hikari.maximum-pool-size=3",
                "--spring.flyway.locations=classpath:db/migration,filesystem:" + backend.resolve("src/test/resources/db/cdc-poc").toString().replace('\\', '/'),
                "--beez.document.object-storage.endpoint=http://127.0.0.1:1", "--beez.document.object-storage.bucket=fixture",
                "--beez.document.object-storage.access-key=fixture", "--beez.document.object-storage.secret-key=fixture",
                "--beez.document.worker-enabled=false", "--beez.validation.worker-enabled=false", "--beez.planning.worker-enabled=false",
                "--beez.ai.base-url=http://127.0.0.1:" + aiPort, "--beez.ai.read-timeout=5s", "--beez.ai.connect-timeout=1s",
                "--beez.cdc.intake.enabled=true", "--beez.cdc.intake.consumer-enabled=false", "--beez.cdc.intake.recovery-poll-millis=1000")
                .directory(backend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private void verifyLiveUi(long intakeId) throws Exception {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path input = Files.createTempFile("beez-229-ui-", ".json");
        Process process = null;
        try {
            Files.writeString(input, JSON.writeValueAsString(Map.of(
                    "backendUrl", "http://127.0.0.1:" + environment.getRequiredProperty("local.server.port"),
                    "projectId", api.projectId(),
                    "proposalId", number("SELECT change_proposal_id FROM published_change_analysis WHERE id=" + intakeId),
                    "email", "leader@test", "password", AccessFixtures.PASSWORD,
                    "expectedStatus", "COMPLETED", "expectedText", "근거 확인 필요",
                    "outputDir", root.resolve("infra/cdc-poc/recovery-229/evidence/ui").toString())));
            process = new ProcessBuilder("node", root.resolve("infra/cdc-poc/ui-229/run.mjs").toString(), input.toString())
                    .directory(root.toFile()).inheritIO().start();
            assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("live UI verification deadline").isTrue();
            assertThat(process.exitValue()).as("live UI verification exit").isZero();
            System.out.println("CDC229 real_browser_backend_query_refresh=PASS");
        } finally {
            stopOwnedProcess(process);
            Files.deleteIfExists(input);
        }
    }

    private static void stopOwnedProcess(Process process) throws InterruptedException {
        if (process == null) return;
        process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }

    private long publish(String key) {
        long base = number("SELECT current_baseline_id FROM project WHERE id=" + api.projectId());
        var caller = CallerContext.human(api.accountId("leader@test"));
        var proposal = proposals.create(caller, api.projectId(), new Input("" + base, "CDC229 " + key, "synthetic-private-marker-229", null, null,
                List.of(new Item("UPDATE", "" + api.designItemId("API-01"), "" + api.currentVersionId("API-01"), "API_CONTRACT", 1,
                        Map.of("method", "GET", "path", "/users/" + key, "description", "synthetic-private-marker-229"))), List.of(), null, null, null), "229-create-" + key);
        var published = publications.approve(caller, api.projectId(), Long.parseLong(proposal.id()),
                new ApproveRequest(proposal.baseBaselineId(), proposal.revision(), proposal.snapshotHash(), "격리 CDC 검증 승인"), "229-approve-" + key);
        return number("SELECT id FROM published_change_analysis WHERE published_baseline_id=" + published.baseline().id());
    }

    @SuppressWarnings("unchecked")
    private void registerProductionConnector() throws Exception {
        var config = new LinkedHashMap<String, String>(JSON.readValue(Files.readString(Path.of("../infra/cdc/dev/intake-connector.json")), Map.class));
        config.putAll(Map.of("database.user", "recovery229_cdc", "database.password", "recovery229_capture_only", "database.dbname", "recovery229",
                "topic.prefix", "recovery229", "slot.name", "recovery229_intake", "publication.name", "recovery229_intake"));
        api.jdbc.sql("CREATE ROLE recovery229_cdc LOGIN REPLICATION PASSWORD 'recovery229_capture_only'").update();
        api.jdbc.sql("GRANT USAGE ON SCHEMA public TO recovery229_cdc").update();
        api.jdbc.sql("GRANT SELECT (id,status) ON public.published_change_analysis TO recovery229_cdc").update();
        api.jdbc.sql("CREATE PUBLICATION recovery229_intake FOR TABLE public.published_change_analysis (id,status)").update();
        assertThat(number("SELECT count(*) FROM pg_roles WHERE rolname='recovery229_cdc' AND NOT rolsuper AND rolreplication")).isEqualTo(1);
        try (var restricted = java.sql.DriverManager.getConnection("jdbc:postgresql://127.0.0.1:15329/recovery229", "recovery229_cdc", "recovery229_capture_only");
             var statement = restricted.createStatement()) {
            try (var allowed = statement.executeQuery("SELECT id,status FROM public.published_change_analysis")) {
                assertThat(allowed.next()).isTrue();
            }
            assertThatThrownBy(() -> statement.executeQuery("SELECT project_id FROM public.published_change_analysis"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(failure -> assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.executeQuery("SELECT email FROM public.account"))
                    .isInstanceOf(java.sql.SQLException.class).satisfies(failure -> assertThat(((java.sql.SQLException) failure).getSQLState()).isEqualTo("42501"));
        }
        System.out.println("CDC229 restricted_capture_role=PASS superuser=false allowed=id,status denied=project_id,account.email");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18329/connectors/recovery229/config"))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(config))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("connector registration HTTP status").isIn(200, 201);
    }

    @SuppressWarnings("unchecked")
    private void collectUntil(KafkaConsumer<String, String> observer, long id, String op) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (EVENTS.stream().noneMatch(e -> eventId(e) == id && op.equals(e.get("op")))) {
            assertThat(System.nanoTime()).as("captured event for intake %s and operation %s", id, op).isLessThan(deadline);
            for (var record : observer.poll(Duration.ofMillis(250))) {
                if (record.value() == null) continue;
                assertThat(record.value()).doesNotContain("synthetic-private-marker-229", "leader@test", "password_hash");
                var root = JSON.readValue(record.value(), Map.class);
                EVENTS.add((Map<String, Object>) root.getOrDefault("payload", root));
            }
        }
    }

    private static long eventId(Map<String, Object> event) {
        if (event.get("after") instanceof Map<?, ?> row && row.get("id") instanceof Number id) return id.longValue();
        return -1;
    }
    private String state(long id) { return api.jdbc.sql("SELECT status FROM published_change_analysis WHERE id=:id").param("id", id).query(String.class).single(); }
    private long number(String sql) { return api.jdbc.sql(sql).query(Long.class).single(); }
    private long attempts(long id) { return number("SELECT count(*) FROM published_analysis_attempt WHERE intake_id=" + id); }
    private void assertOneAttempt(long id) {
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(number("SELECT attempt FROM published_change_analysis WHERE id=" + id)).isEqualTo(1);
        // Empty candidates without supporting evidence must never be labelled a confirmed zero-impact result.
        assertThat(api.jdbc.sql("SELECT evaluation_status FROM published_change_analysis WHERE id=:id").param("id", id)
                .query(String.class).single()).isEqualTo("INSUFFICIENT_EVIDENCE");
    }
    private static long committed(Admin admin) {
        try {
            var offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(PARTITION);
            return offset == null ? 0 : offset.offset();
        } catch (Exception failure) { throw new IllegalStateException("Unable to inspect isolated group offset", failure); }
    }
    private static void awaitOffset(Admin admin, long end) throws InterruptedException {
        await(() -> committed(admin) >= end, 45, "durable group offset " + end);
    }
    private static void awaitEmptyGroup(Admin admin) throws InterruptedException {
        await(() -> {
            try { return admin.describeConsumerGroups(List.of(GROUP)).all().get(5, TimeUnit.SECONDS).get(GROUP).members().isEmpty(); }
            catch (Exception failure) { return false; }
        }, 45, "stopped group");
    }
    private static void await(BooleanSupplier condition, int seconds, String label) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out: " + label);
            Thread.sleep(100);
        }
    }
}
