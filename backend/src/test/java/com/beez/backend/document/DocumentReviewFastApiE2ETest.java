package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.document.service.DocumentReviewService;
import com.beez.backend.document.dto.SuggestionDecisionRequest;
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

/** Opt-in real Spring service and FastAPI HTTP boundary, with a fake model. */
@EnabledIfEnvironmentVariable(named = "BEEZ_RUN_AI_E2E", matches = "true")
class DocumentReviewFastApiE2ETest extends IntegrationTestSupport {
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
        Path script = backend.resolve("src/test/resources/document_review_e2e_fastapi.py");
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
        Runtime.getRuntime().addShutdownHook(new Thread(DocumentReviewFastApiE2ETest::stop));
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

    @Test void reviewAndUsageAreStoredAcrossRealHttp() {
        long document = upload("# 계획\n로그인한다.\n");
        var result = reviews.review(author, projectId, document, 1, "review-e2e");
        assertThat(result.contractVersion()).isEqualTo(2);
        assertThat(result.reviewStatus()).isEqualTo("PARTIAL");
        assertThat(result.steps()).hasSize(4);
        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().getFirst().type()).isEqualTo("COMMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed=false", Integer.class, projectId)).isEqualTo(1);
    }

    @Test void approvedReferencesReachModelAndCompleteIsStored() {
        long document = upload("# 계획\n로그인한다.\n");
        for (String kind : List.of("API_SPEC", "ERD", "REQUIREMENTS")) {
            long reference = upload(kind, "# " + kind + "\n승인된 참고 내용\n");
            jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leaderId, reference);
        }
        var result = reviews.review(author, projectId, document, 1, "complete-e2e");
        assertThat(result.reviewStatus()).isEqualTo("COMPLETE");
        assertThat(result.unavailableReferences()).isEmpty();
        assertThat(result.steps()).hasSize(4);
        assertThat(result.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo("COMPLETE"));
        assertThat(result.references()).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed=false", Integer.class, projectId)).isEqualTo(1);
    }

    @Test void timeoutIsNotStoredAsPartialAndFailureUsageSurvives() {
        long document = upload("# E2E_TIMEOUT\n");
        assertThatThrownBy(() -> reviews.review(author, projectId, document, 1, "timeout-e2e"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("review_unavailable"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed=true", Integer.class, projectId)).isEqualTo(1);
    }

    @Test void invalidDeletionAndConflictingPatchesFailBeforeSavingReviewAndPreserveUsage() {
        for (String content : List.of("# E2E_PATCH_DELETE\n", "# E2E_PATCH_CONFLICT\nfirst-token second-token\n")) {
            long document = upload(content.contains("E2E_PATCH_DELETE") ? "PLAN" : "API_SPEC", content);
            assertThatThrownBy(() -> reviews.review(author, projectId, document, 1, UUID.randomUUID().toString()))
                    .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("review_unavailable"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed=true", Integer.class, projectId)).isEqualTo(2);
    }

    @Test void independentPatchesCanBeAppliedThroughBackendInReverseOrder() {
        long document = upload("# E2E_PATCH_VALID\nred-item blue-item\n");
        var review = reviews.review(author, projectId, document, 1, "patch-order-e2e");
        assertThat(review.suggestions()).hasSize(2);
        var second = review.suggestions().get(1);
        var first = review.suggestions().getFirst();
        var afterSecond = reviews.decideSuggestion(author, projectId, document, Long.parseLong(review.id()),
                Long.parseLong(second.id()), new SuggestionDecisionRequest(1, "APPLY"));
        var afterFirst = reviews.decideSuggestion(author, projectId, document, Long.parseLong(review.id()),
                Long.parseLong(first.id()), new SuggestionDecisionRequest(afterSecond.revision(), "APPLY"));
        assertThat(afterFirst.content()).contains("scarlet-item azure-item");
        assertThat(afterFirst.revision()).isEqualTo(3);
    }

    @Test void uploadedDoubleBomCanBeReviewedAndPatched() {
        long document = upload("\uFEFF\uFEFF# E2E_PATCH_VALID\nred-item blue-item\n");
        assertThat(reviews.get(author, projectId, document).content()).startsWith("\uFEFF#");
        var review = reviews.review(author, projectId, document, 1, "bom-patch-e2e");
        assertThat(review.suggestions()).hasSize(2);
        var first = reviews.decideSuggestion(author, projectId, document, Long.parseLong(review.id()),
                Long.parseLong(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "APPLY"));
        assertThat(first.content()).doesNotStartWith("\uFEFF").contains("scarlet-item blue-item");
        var second = reviews.decideSuggestion(author, projectId, document, Long.parseLong(review.id()),
                Long.parseLong(review.suggestions().get(1).id()), new SuggestionDecisionRequest(first.revision(), "APPLY"));
        assertThat(second.content()).contains("scarlet-item azure-item");
    }

    @Test void bomRemovalCannotInvalidateAnotherPendingPatch() {
        long document = upload("\uFEFF\uFEFF# E2E_BOM_PREFIX\nother-token\n");
        assertThat(reviews.get(author, projectId, document).content()).startsWith("\uFEFF#");
        assertThatThrownBy(() -> reviews.review(author, projectId, document, 1, "bom-conflict-e2e"))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.getCode()).isEqualTo("review_unavailable"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed=true", Integer.class, projectId)).isEqualTo(1);
    }
}
