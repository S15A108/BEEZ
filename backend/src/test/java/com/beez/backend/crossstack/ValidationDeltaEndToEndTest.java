package com.beez.backend.crossstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

import com.beez.backend.document.service.HybridDocumentCandidateSearch;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.worker.ValidationWorker;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Real #77 completion-report HTTP -> PostgreSQL queue -> #74 worker -> AiClient ->
 * FastAPI route -> LLMClient, with a fake model and bounded search stub. The stub
 * intentionally removes external search/embedding and is not a search-quality test.
 * Set BEEZ_RUN_VALIDATION_DELTA_E2E=true and optionally BEEZ_E2E_PYTHON.
 */
@EnabledIfEnvironmentVariable(named = "BEEZ_RUN_VALIDATION_DELTA_E2E", matches = "true")
class ValidationDeltaEndToEndTest extends IntegrationTestSupport {
    private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String NONCE = UUID.randomUUID().toString() + UUID.randomUUID();
    private static Process server;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Path backend = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path ai = backend.resolve("../ai").normalize();
        Path fixture = backend.resolve("src/test/resources/validation_delta_e2e_fastapi.py");
        String override = System.getenv("BEEZ_E2E_PYTHON");
        Path python = override == null || override.isBlank()
                ? ai.resolve(System.getProperty("os.name").startsWith("Windows")
                    ? ".venv/Scripts/python.exe" : ".venv/bin/python")
                : Path.of(override);
        if (!Files.isRegularFile(python) || !Files.isRegularFile(fixture)) {
            throw new IllegalStateException("Missing AI test Python or DELTA fixture");
        }
        var builder = new ProcessBuilder(python.toString(), "-u", fixture.toString(), Integer.toString(port));
        builder.directory(ai.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PYTHONPATH", ai.toString());
        builder.environment().put("BEEZ_DELTA_E2E_TOKEN", TOKEN);
        builder.environment().put("BEEZ_DELTA_E2E_NONCE", NONCE);
        server = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(ValidationDeltaEndToEndTest::stopServer));
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
            var ready = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__ready"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            boolean started = false;
            for (int i = 0; i < 150 && server.isAlive(); i++) {
                try {
                    var response = http.send(ready, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && NONCE.equals(response.body())) {
                        started = true;
                        break;
                    }
                } catch (java.io.IOException ignored) { /* child still starting */ }
                Thread.sleep(100);
            }
            if (!started) throw new IllegalStateException("DELTA FastAPI fixture did not start");
        } catch (Exception failure) {
            stopServer();
            throw failure;
        }
        registry.add("beez.ai.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("beez.ai.internal-token", () -> TOKEN);
        registry.add("beez.ai.connect-timeout", () -> "3s");
        registry.add("beez.ai.read-timeout", () -> "30s");
    }

    @AfterAll
    static void stopServer() {
        if (server == null || !server.isAlive()) return;
        server.destroy();
        try {
            if (!server.waitFor(5, TimeUnit.SECONDS)) server.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.destroyForcibly();
        }
    }

    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @MockitoBean HybridDocumentCandidateSearch search;
    private TaskApiSupport api;
    private long project;
    private long member;

    @BeforeEach
    void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        member = api.accountId("member@test");
        // The shared fixture's API version predates the AI content contract;
        // publish a valid immutable version so the real AI boundary can validate it.
        long item = api.designItemId("API-01");
        api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values(:item,2,'APPROVED','GET users',1,cast(:content as jsonb),'delta-e2e-hash','HUMAN',now())
                """).param("item", item).param("content", """
                        {"method":"GET","path":"/users","description":"List users",
                         "request_fields":[{"name":"limit","location":"query","type":"integer","required":false}],
                         "responses":[{"status_code":"200","fields":[{"name":"id","location":"body","type":"integer","required":true}]}]}
                        """).update();
        long version = api.jdbc.sql("select id from design_item_version where design_item_id=:item and version_no=2")
                .param("item", item).query(Long.class).single();
        long baseline = api.publish(2, Map.of("API-01", version, "API-02", api.currentVersionId("API-02")));
        when(search.search(any(), eq(project), anyString(), isNull(), isNull(), eq(100)))
                .thenReturn(new HybridDocumentCandidateSearch.Result(Long.toString(baseline), List.of(), false, false));
    }

    @Test
    void reportRunsActualWorkerAndFastApiAndBlockingMismatchPreventsCompletion() {
        long[] result = reportAndRun("fixture completion differs");
        long task = result[0];
        long validation = result[1];
        assertThat(api.jdbc.sql("select status from validation where id=:id").param("id", validation)
                .query(String.class).single()).isEqualTo("NEEDS_REVIEW");
        assertThat(api.jdbc.sql("select status from task where id=:id").param("id", task)
                .query(String.class).single()).isEqualTo("WAITING");
        var finding = api.jdbc.sql("select kind,severity,check_key,message from validation_finding where validation_id=:id and source='MODEL'")
                .param("id", validation).query().singleRow();
        assertThat(finding).containsEntry("kind", "MISMATCH").containsEntry("severity", "BLOCKING")
                .containsEntry("check_key", "completion-delta").containsEntry("message", "fixture completion differs");
    }

    @Test
    void matchingReportPassesActualWorkerAndCompletesTask() {
        long[] result = reportAndRun("fixture completion matches");
        assertThat(api.jdbc.sql("select status from validation where id=:id").param("id", result[1])
                .query(String.class).single()).isEqualTo("PASS");
        var task = api.jdbc.sql("select status, completed_at from task where id=:id").param("id", result[0])
                .query().singleRow();
        assertThat(task).containsEntry("status", "COMPLETED");
        assertThat(task.get("completed_at")).isNotNull();
        assertThat(api.jdbc.sql("select count(*) from validation_finding where validation_id=:id and source='MODEL'")
                .param("id", result[1]).query(Integer.class).single()).isZero();
    }

    /** Both cases run the same real submission and worker path; only fake-model report text differs. */
    private long[] reportAndRun(String reportText) {
        String tasks = "/api/projects/" + project + "/tasks";
        var created = api.post(tasks, api.planJson("DELTA end-to-end", api.implementsRef("API-01"), "[]"), member);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long task = TaskApiSupport.longField(created.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t")
                .param("t", task).query(Long.class).single();
        long lock = TaskApiSupport.longField(created.body(), "lock_version");
        long pass = api.passValidation(task);
        String eventPath = tasks + "/" + task + "/state-events";
        var start = api.post(eventPath, "{\"event\":\"START\",\"plan_revision_id\":\"" + revision
                + "\",\"expected_lock_version\":" + lock + ",\"validation_id\":\"" + pass + "\"}", member);
        assertThat(start.statusCode()).as(start.body()).isEqualTo(200);
        long inProgressLock = TaskApiSupport.longField(start.body(), "lock_version");
        var completed = api.post(eventPath, "{\"event\":\"COMPLETED\",\"plan_revision_id\":\"" + revision
                + "\",\"expected_lock_version\":" + inProgressLock
                + ",\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"" + reportText + "\"}]}", member);
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(202);
        long validation = TaskApiSupport.longField(completed.body(), "delta_validation_id");

        assertThat(worker.runOnce()).isTrue();
        return new long[] {task, validation};
    }
}
