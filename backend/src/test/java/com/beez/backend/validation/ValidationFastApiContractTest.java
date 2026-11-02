package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.ai.AiClient;
import com.beez.backend.config.BeezProperties;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Java AiClient to the real FastAPI AI-A02 route over loopback HTTP, using a fake LLM.
 * #77 is not merged: the DELTA payload below is a producer-contract fixture, not the #77 worker.
 * Enable with BEEZ_RUN_VALIDATION_HTTP_E2E=true and optionally BEEZ_E2E_PYTHON.
 */
@EnabledIfEnvironmentVariable(named = "BEEZ_RUN_VALIDATION_HTTP_E2E", matches = "true")
class ValidationFastApiContractTest {
    @Test
    @SuppressWarnings("unchecked")
    void completionDeltaReachesTheModelAndItsFindingReturnsOverHttp() throws Exception {
        Path backend = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path ai = backend.resolve("../ai").normalize();
        Path fixture = backend.resolve("src/test/resources/validation_contract_fastapi.py");
        String override = System.getenv("BEEZ_E2E_PYTHON");
        Path python = override == null || override.isBlank()
                ? ai.resolve(System.getProperty("os.name").startsWith("Windows")
                    ? ".venv/Scripts/python.exe" : ".venv/bin/python")
                : Path.of(override);
        if (!Files.isRegularFile(python) || !Files.isRegularFile(fixture)) {
            throw new IllegalStateException("Missing AI test Python or validation fixture");
        }

        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        String token = UUID.randomUUID().toString() + UUID.randomUUID();
        String nonce = UUID.randomUUID().toString() + UUID.randomUUID();
        var builder = new ProcessBuilder(python.toString(), "-u", fixture.toString(), Integer.toString(port));
        builder.directory(ai.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("PYTHONPATH", ai.toString());
        builder.environment().put("BEEZ_VALIDATION_CONTRACT_TOKEN", token);
        builder.environment().put("BEEZ_VALIDATION_CONTRACT_NONCE", nonce);
        Process server = builder.start();
        try {
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
            var ready = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__ready"))
                    .timeout(Duration.ofSeconds(1)).GET().build();
            boolean started = false;
            for (int i = 0; i < 150 && server.isAlive(); i++) {
                try {
                    var response = http.send(ready, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200 && nonce.equals(response.body())) {
                        started = true;
                        break;
                    }
                } catch (java.io.IOException ignored) { /* startup in progress */ }
                Thread.sleep(100);
            }
            assertThat(started).as("isolated FastAPI fixture started").isTrue();

            var mapper = JsonMapper.builder().build();
            Map<String, Object> input = mapper.readValue(
                    Files.readString(backend.resolve("../docs/api/examples/validation/request.json")), Map.class);
            input.put("request_key", "validate-601-attempt-1");
            input.put("completion_delta", List.of(Map.of("kind", "CHANGED", "text", "fixture completion differs from plan")));
            input.put("checks", List.of(Map.of("key", "completion-delta", "kind", "PLAN_COMPLETENESS",
                    "design_item_version_ids", List.of(), "related_plan_revision_ids", List.of())));
            input.put("evidence", List.of());
            input.put("unresolved", List.of());

            var aiConfig = new BeezProperties.Ai("http://127.0.0.1:" + port, token,
                    Duration.ofSeconds(3), Duration.ofSeconds(30));
            var client = new AiClient(new BeezProperties(null, aiConfig, null));
            Map<String, Object> response = client.validate(input);
            assertThat(response).containsEntry("origin", "MODEL");
            var result = (Map<String, Object>) response.get("result");
            assertThat(result).containsEntry("analysis_status", "COMPLETE");
            assertThat(result.get("checked_keys")).isEqualTo(List.of("completion-delta"));
            var findings = (List<Map<String, Object>>) result.get("findings");
            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst()).containsEntry("check_key", "completion-delta")
                    .containsEntry("kind", "MISMATCH")
                    .containsEntry("message", "fixture completion differs from plan");
            assertThat((List<?>) response.get("usage")).hasSize(1);
        } finally {
            server.destroy();
            if (!server.waitFor(5, TimeUnit.SECONDS)) {
                server.destroyForcibly();
                server.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
