package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Opt-in integration: real PostgreSQL, Spring service and HTTP to a separate real FastAPI.
 * A deterministic fake model is the default. The GMS variant is explicit and separate.
 * There is no public BE endpoint for this workflow; the Spring service is invoked directly.
 */
@EnabledIfEnvironmentVariable(named = "BEEZ_RUN_AI_E2E", matches = "true")
class ImpactBackendFastApiE2ETest extends IntegrationTestSupport {
    private static Process aiServer;
    private static int aiPort;
    private static final boolean REAL_GMS = "true".equals(System.getenv("BEEZ_IMPACT_E2E_REAL_GMS"));
    private static final String INTERNAL_TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String READINESS_NONCE = UUID.randomUUID().toString() + UUID.randomUUID();

    @DynamicPropertySource
    static void isolatedAiProperties(DynamicPropertyRegistry registry) throws Exception {
        startFastApi();
        registry.add("beez.ai.base-url", () -> "http://127.0.0.1:" + aiPort);
        registry.add("beez.ai.internal-token", () -> INTERNAL_TOKEN);
        registry.add("beez.ai.connect-timeout", () -> "3s");
        registry.add("beez.ai.read-timeout", () -> "30s");
    }

    private static void startFastApi() throws Exception {
        try (var port = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            aiPort = port.getLocalPort();
        }
        Path backend = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path ai = backend.resolve("../ai").normalize();
        Path fixture = backend.resolve("src/test/resources/impact_e2e_fastapi.py");
        String override = System.getenv("BEEZ_E2E_PYTHON");
        Path python = override == null || override.isBlank()
                ? ai.resolve(System.getProperty("os.name").startsWith("Windows")
                    ? ".venv/Scripts/python.exe" : ".venv/bin/python")
                : Path.of(override);
        if (!Files.isRegularFile(python) || !Files.isRegularFile(fixture)) {
            throw new IllegalStateException("Missing AI test Python or FastAPI fixture");
        }
        String gmsTestKey = System.getenv("BEEZ_IMPACT_E2E_GMS_KEY");
        if (REAL_GMS && (gmsTestKey == null || gmsTestKey.isBlank())) {
            throw new IllegalStateException("Real GMS test key missing in Gradle test process environment");
        }
        ProcessBuilder builder = new ProcessBuilder(python.toString(), "-u", fixture.toString(), Integer.toString(aiPort));
        builder.directory(ai.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        // Isolate Settings from inherited AI configuration; keep explicit BEEZ_IMPACT_E2E_* controls.
        builder.environment().keySet().removeIf(name -> {
            String normalized = name.toUpperCase(Locale.ROOT);
            return normalized.startsWith("IMPACT_") || normalized.startsWith("GMS_")
                    || normalized.startsWith("OPENAI_") || normalized.equals("AI_INTERNAL_TOKEN")
                    || normalized.equals("BEE_DEFAULT_MODEL") || normalized.equals("BEE_COMPLEX_MODEL")
                    || normalized.equals("TIKTOKEN_CACHE_DIR");
        });
        builder.environment().put("PYTHONPATH", ai.toString());
        builder.environment().put("BEEZ_IMPACT_E2E_INTERNAL_TOKEN", INTERNAL_TOKEN);
        builder.environment().put("BEEZ_IMPACT_E2E_READINESS_NONCE", READINESS_NONCE);
        if (!REAL_GMS) builder.environment().remove("BEEZ_IMPACT_E2E_GMS_KEY");
        aiServer = builder.start();
        AtomicReference<String> startupError = new AtomicReference<>();
        Thread stderrReader = Thread.ofVirtual().start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(aiServer.getErrorStream()))) {
                for (String line; (line = reader.readLine()) != null;) {
                    // Never forward raw diagnostics: they may contain provider input or secrets.
                    if (line.equals("BEEZ_E2E_STARTUP_ERROR_KEY_ERROR")
                            || line.equals("BEEZ_E2E_STARTUP_ERROR_VALIDATION_ERROR")
                            || line.equals("BEEZ_E2E_STARTUP_ERROR_IMPORT_ERROR")
                            || line.equals("BEEZ_E2E_STARTUP_ERROR_OTHER")) {
                        startupError.set(line);
                    }
                }
            } catch (IOException ignored) { /* child stream closed */ }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(ImpactBackendFastApiE2ETest::stopFastApi));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        var health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + aiPort + "/__e2e_ready"))
                .timeout(Duration.ofSeconds(1)).GET().build();
        for (int i = 0; i < 150; i++) {
            if (!aiServer.isAlive()) {
                stderrReader.join(1000);
                throw new IllegalStateException("AI test server exited before becoming ready; exit="
                        + aiServer.exitValue() + ", safe_error=" + startupError.get());
            }
            try {
                var response = client.send(health, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && READINESS_NONCE.equals(response.body()) && aiServer.isAlive()) return;
            } catch (IOException ignored) { /* app is still starting */ }
            Thread.sleep(100);
        }
        stopFastApi();
        throw new IllegalStateException("AI test server did not become ready");
    }

    @AfterAll
    static void stopFastApi() {
        if (aiServer == null || !aiServer.isAlive()) return;
        aiServer.destroy();
        try {
            if (!aiServer.waitFor(5, TimeUnit.SECONDS)) aiServer.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            aiServer.destroyForcibly();
        }
    }

    @Autowired DataSource dataSource;
    @Autowired ImpactAnalysisCoordinator coordinator;
    @Autowired AiClient aiClient;
    @Autowired ProposalCommandService proposals;
    private TaskApiSupport api;
    private long projectId;
    private long member;
    private long baseline;

    @BeforeEach
    void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:project")
                .param("project", projectId).query(Long.class).single();
    }

    private long submittedProposal(String reason, String key) {
        var item = new ProposalDtos.Item("UPDATE", Long.toString(api.designItemId("API-01")),
                Long.toString(api.currentVersionId("API-01")), null, 1,
                Map.of("description", "fictional login response changed", "method", "GET", "path", "/v2/users"));
        var request = new ProposalDtos.Input(Long.toString(baseline), "fixture impact", reason,
                null, null, List.of(item), List.of(), null, null, null);
        var submitted = proposals.create(CallerContext.human(member), projectId, request, key);
        return Long.parseLong(submitted.id());
    }

    @Test
    void submittedRevisionCrossesRealHttpBoundaryWithConnectedRules() {
        long proposal = submittedProposal("fictional login change", "impact-http-success");
        var result = coordinator.analyze(CallerContext.human(member), projectId, proposal, "e2e-http-success");

        var evidence = ((Map<?, ?>) result.aiEnvelope().get("result")).get("evidence_status");
        assertThat(result.status()).isEqualTo("SUFFICIENT".equals(evidence)
                ? ImpactAnalysisCoordinator.Status.ANALYZED : ImpactAnalysisCoordinator.Status.INSUFFICIENT_EVIDENCE);
        assertThat(result.errorCode()).isNull();
        assertThat(result.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
        assertThat(result.aiEnvelope()).containsEntry("origin", "MODEL");
        assertThat(result.input().proposal().revisionNo()).isEqualTo(1);
        assertThat(result.input().proposal().snapshotHash()).startsWith("sha256:");
        assertThat(result.usage()).hasSize(1);
        var usage = (Map<?, ?>) result.usage().getFirst();
        assertThat(usage.get("feature")).isEqualTo("CHANGE_IMPACT");
        assertThat(usage.get("failed")).isEqualTo(false);
        if (!REAL_GMS) {
            assertThat(usage.get("input_tokens")).isEqualTo(11);
            assertThat(usage.get("output_tokens")).isEqualTo(7);
            var payload = (Map<?, ?>) result.aiEnvelope().get("result");
            var candidates = (List<?>) payload.get("candidates");
            assertThat(candidates).hasSize(1);
            assertThat(((Map<?, ?>) candidates.getFirst()).get("design_item_id"))
                    .isEqualTo(Long.toString(api.designItemId("API-01")));
        } else {
            var payload = (Map<?, ?>) result.aiEnvelope().get("result");
            var candidates = (List<?>) payload.get("candidates");
            System.out.println("REAL_GMS_SMOKE status=" + result.status()
                    + " evidence_status=" + payload.get("evidence_status") + " candidate_count=" + candidates.size()
                    + " usage_reported=" + result.usageStatus() + " tokens_known=" + usage.get("tokens_known")
                    + " input_tokens=" + usage.get("input_tokens") + " output_tokens=" + usage.get("output_tokens"));
        }
    }

    @Test
    void commonAiClientChunksMarkdownOverRealHttp() throws Exception {
        String markdown = "# Design\n\nA fictional policy.\n";
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String hash = java.util.HexFormat.of().formatHex(digest);
        Map<String, Object> request = Map.of(
                "project_id", "101",
                "document_version_id", "202",
                "request_key", "e2e-chunk-1",
                "kind", "PLAN",
                "media_type", "text/markdown",
                "content", markdown,
                "content_hash", hash,
                "text_hash", hash,
                "normalization_profile", "utf8-lf-v1",
                "chunking_profile_id", "md-structure-v1");

        var response = aiClient.documentChunks(request);
        assertThat(response).containsEntry("origin", "PARSER");
        var result = (Map<?, ?>) response.get("result");
        assertThat(result.get("contract_version")).isEqualTo("document-chunks-v1");
        assertThat((java.util.List<?>) result.get("chunks")).isNotEmpty();
    }

    @Test
    void commonAiClientEmbedsTextOverRealHttpWithFakeProvider() {
        var response = aiClient.documentEmbeddings(Map.of(
                "project_id", "101",
                "request_key", "e2e-embedding-1",
                "texts", java.util.List.of(Map.of("key", "chunk-1", "text", "fictional content"))));
        assertThat(response).containsEntry("origin", "MODEL");
        var result = (Map<?, ?>) response.get("result");
        assertThat(result.get("dimension")).isEqualTo(3);
        assertThat((java.util.List<?>) result.get("vectors")).hasSize(1);
        assertThat((java.util.List<?>) response.get("usage")).hasSize(1);
    }

    @Test
    void modelTimeoutCrossesRealHttpBoundaryAndRetainsFailureUsage() {
        assumeFalse(REAL_GMS, "Timeout is deterministic only with the fake provider");
        long proposal = submittedProposal("E2E_TIMEOUT", "impact-http-timeout");
        var result = coordinator.analyze(CallerContext.human(member), projectId, proposal, "e2e-http-timeout");

        assertThat(result.status()).isEqualTo(ImpactAnalysisCoordinator.Status.AI_FAILED);
        assertThat(result.errorCode()).isEqualTo("model_timeout");
        assertThat(result.usageStatus()).isEqualTo(ImpactAnalysisCoordinator.UsageStatus.REPORTED);
        assertThat(result.usage()).hasSize(1);
        var usage = (Map<?, ?>) result.usage().getFirst();
        assertThat(usage.get("failed")).isEqualTo(true);
        assertThat(usage.get("input_tokens")).isEqualTo(3);
        assertThat(usage.get("output_tokens")).isEqualTo(1);
    }
}
