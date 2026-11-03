package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.document.service.DocumentReviewService;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Explicitly opt-in, one billable real GMS call through Spring and FastAPI. */
@EnabledIfEnvironmentVariable(named = "BEEZ_RUN_REAL_GMS_E2E", matches = "true")
class DocumentReviewRealGmsE2ETest extends IntegrationTestSupport {
    private static final String TOKEN = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String NONCE = UUID.randomUUID().toString() + UUID.randomUUID();
    private static Process ai;
    private static int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        Path backend = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path aiDir = backend.resolve("../ai").normalize();
        String override = System.getenv("BEEZ_E2E_PYTHON");
        Path python = override == null || override.isBlank()
                ? aiDir.resolve(System.getProperty("os.name").startsWith("Windows")
                        ? ".venv/Scripts/python.exe" : ".venv/bin/python") : Path.of(override);
        Path script = backend.resolve("src/test/resources/document_review_real_gms_fastapi.py");
        if (!Files.isRegularFile(python) || !Files.isRegularFile(script)) throw new IllegalStateException("Missing test Python or fixture");
        var builder = new ProcessBuilder(python.toString(), "-u", script.toString(), Integer.toString(port));
        builder.directory(aiDir.toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(name -> {
            String upper = name.toUpperCase(Locale.ROOT);
            return upper.startsWith("GMS_") || upper.startsWith("OPENAI_") || upper.equals("AI_INTERNAL_TOKEN")
                    || upper.equals("BEE_DEFAULT_MODEL") || upper.equals("BEE_COMPLEX_MODEL");
        });
        builder.environment().put("PYTHONPATH", aiDir.toString());
        builder.environment().put("BEEZ_REVIEW_E2E_TOKEN", TOKEN);
        builder.environment().put("BEEZ_REVIEW_E2E_NONCE", NONCE);
        ai = builder.start();
        Runtime.getRuntime().addShutdownHook(new Thread(DocumentReviewRealGmsE2ETest::stop));
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        var health = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/__e2e_ready"))
                .timeout(Duration.ofSeconds(1)).GET().build();
        boolean ready = false;
        for (int i = 0; i < 150 && ai.isAlive(); i++) {
            try {
                var response = client.send(health, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && NONCE.equals(response.body())) { ready = true; break; }
            } catch (java.io.IOException ignored) { }
            Thread.sleep(100);
        }
        if (!ready) { stop(); throw new IllegalStateException("FastAPI test server failed to start"); }
        registry.add("beez.ai.base-url", () -> "http://127.0.0.1:" + port);
        registry.add("beez.ai.internal-token", () -> TOKEN);
        registry.add("beez.ai.connect-timeout", () -> "3s");
        registry.add("beez.ai.read-timeout", () -> "20s");
    }

    @AfterAll static void stop() {
        if (ai == null || !ai.isAlive()) return;
        ai.destroy();
        try { if (!ai.waitFor(5, TimeUnit.SECONDS)) ai.destroyForcibly(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); ai.destroyForcibly(); }
    }

    @Autowired DocumentApplicationService documents;
    @Autowired DocumentReviewService reviews;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DocumentStorage storage;
    long projectId;
    CallerContext author;
    long leaderId;

    @BeforeEach void setup() throws Exception {
        var project = fixtures().projectWithLeader();
        projectId = project.projectId();
        leaderId = project.leaderAccountId();
        long account = fixtures().account();
        fixtures().member(projectId, account, ProjectRole.MEMBER);
        author = CallerContext.human(account);
        when(storage.store(any(), anyString())).thenAnswer(call -> UUID.randomUUID().toString());
    }

    long upload(String content) { return upload("PLAN", content); }

    long upload(String kind, String content) {
        var response = documents.upload(author, projectId, DocumentKind.valueOf(kind), kind, null,
                "plan.md", content.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
        return Long.parseLong(((Map<?, ?>) response.get("document")).get("id").toString());
    }

    @Test void realModelReviewAndUsageAreStoredAcrossHttp() {
        long document = upload("# 계획\n사용자가 이메일로 로그인한다.\n");
        for (String kind : List.of("API_SPEC", "ERD", "REQUIREMENTS")) {
            long reference = upload(kind, "# " + kind + "\n이메일 로그인과 사용자 계정을 정의한다.\n");
            jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leaderId, reference);
        }
        // One real GMS call. All supplied text is synthetic.
        var result = reviews.review(author, projectId, document, 1, "live-gms-e2e");
        assertThat(result.contractVersion()).isEqualTo(2);
        assertThat(result.reviewStatus()).isEqualTo("COMPLETE");
        assertThat(result.unavailableReferences()).isEmpty();
        assertThat(result.steps()).hasSize(4);
        assertThat(result.references()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed=false", Integer.class, projectId)).isEqualTo(1);
    }
}
