package com.beez.backend.impactanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.beez.backend.ai.AiClient;
import com.beez.backend.cdc.observability.PublishedAnalysisMetricsReader;
import com.beez.backend.changeproposal.dto.ProposalDtos;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in integration: real PostgreSQL, Spring service and HTTP to a separate real FastAPI.
 * A deterministic fake model is the default. The GMS variant is explicit and separate.
 * Most tests call the Spring service directly; the owner evaluation uses the BE HTTP API.
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
    @Autowired ImpactContextQuery contexts;
    @Autowired AiClient aiClient;
    @Autowired ProposalCommandService proposals;
    @Autowired ProposalPublicationService publications;
    @Autowired PublishedAnalysisProcessor publishedProcessor;
    @Autowired PublishedAnalysisMetricsReader metricsReader;
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

    /** Opt-in paid test: fixture v2 models the pre-existing unevaluated document change;
     * v3 is actually published and processed through BE, FastAPI and real GMS. */
    @Test
    void publishedAnalysisAfterUnsupportedDocumentChangeUsesRealGms() {
        assumeTrue(REAL_GMS, "This test requires the explicit real GMS opt-in");
        long leader = api.accountId("leader@test");
        var actor = CallerContext.human(leader);
        long currentItem = api.currentVersionId("API-01");

        // This initial row and v2 are synthetic history in the isolated PostgreSQL database.
        api.jdbc.sql("INSERT INTO published_change_analysis(project_id,published_baseline_id,status,finished_at) "
                + "VALUES(:p,:b,'NOT_EVALUATED',clock_timestamp())")
                .param("p", projectId).param("b", baseline).update();
        long secondBaseline = api.publish(2, Map.of("API-01", currentItem));
        // The legacy synthetic publisher hard-codes CP-2 while production keys use
        // the allocated id. Reserve id 2 so the real v3 create does not reuse that key.
        api.jdbc.sql("SELECT nextval(pg_get_serial_sequence('change_proposal','id'))")
                .query(Long.class).single();
        long predecessor = api.jdbc.sql("""
                INSERT INTO published_change_analysis(project_id,published_baseline_id,change_proposal_id,status,
                    evaluation_status,error_code,finished_at)
                SELECT project_id,id,change_proposal_id,'NOT_EVALUATED','NOT_EVALUATED',
                    'document_change_unavailable',clock_timestamp() FROM baseline WHERE id=:b RETURNING id
                """).param("b", secondBaseline).query(Long.class).single();

        assertThat(api.post("/api/projects/" + projectId + "/tasks",
                api.planJson("Synthetic API compatibility review", api.readsRef("API-01"), "[]"), member)
                .statusCode()).isEqualTo(201);
        var item = new ProposalDtos.Item("UPDATE", Long.toString(api.designItemId("API-01")),
                Long.toString(api.currentVersionId("API-01")), "API_CONTRACT", 1,
                Map.of("description", "fictional user API path change", "method", "GET", "path", "/users/v3"));
        var proposal = proposals.create(actor, projectId,
                new ProposalDtos.Input(Long.toString(secondBaseline), "Synthetic API update", "fictional user API change",
                        null, null, List.of(item), List.of(), null, null, null), "live-cdc-v3-create");
        var approved = publications.approve(actor, projectId, Long.parseLong(proposal.id()),
                new ApproveRequest(proposal.baseBaselineId(), proposal.revision(), proposal.snapshotHash(),
                        "Synthetic live test of post-publication analysis"), "live-cdc-v3-approve");
        long intake = api.jdbc.sql("SELECT id FROM published_change_analysis WHERE published_baseline_id=:b")
                .param("b", Long.parseLong(approved.baseline().id())).query(Long.class).single();
        assertThat(metricsReader.read().blockedProjects()).isZero();
        assertThat(metricsReader.read().notEvaluatedRequiresReview()).isEqualTo(1);

        publishedProcessor.process(intake);

        var result = api.jdbc.sql("SELECT status,evaluation_status,error_code,usage_status FROM published_change_analysis WHERE id=:id")
                .param("id", intake).query().singleRow();
        assertThat(result).containsEntry("status", "COMPLETED").containsEntry("usage_status", "REPORTED");
        assertThat(result.get("evaluation_status")).isIn("COMPLETED", "INSUFFICIENT_EVIDENCE");
        var usage = api.jdbc.sql("SELECT origin,model,failed,tokens_known,input_tokens,output_tokens FROM llm_usage "
                + "WHERE project_id=:p AND feature='CHANGE_IMPACT'")
                .param("p", projectId).query().singleRow();
        assertThat(usage).containsEntry("origin", "model").containsEntry("failed", false);
        assertThat(usage.get("model").toString()).isNotBlank();
        assertThat(api.jdbc.sql("SELECT status,evaluation_status,error_code FROM published_change_analysis WHERE id=:id")
                .param("id", predecessor).query().singleRow())
                .containsEntry("status", "NOT_EVALUATED").containsEntry("evaluation_status", "NOT_EVALUATED")
                .containsEntry("error_code", "document_change_unavailable");
        assertThat(metricsReader.read().notEvaluatedRequiresReview()).isEqualTo(1);
        System.out.println("REAL_GMS_CDC_SMOKE lifecycle=" + result.get("status")
                + " evaluation=" + result.get("evaluation_status") + " error_code=" + result.get("error_code")
                + " usage_status=" + result.get("usage_status") + " model=" + usage.get("model")
                + " tokens_known=" + usage.get("tokens_known") + " input_tokens=" + usage.get("input_tokens")
                + " output_tokens=" + usage.get("output_tokens"));
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

    /**
     * Synthetic quality observations for #189 through the real retry and GET APIs, persistence,
     * HTTP to FastAPI and (when explicitly enabled) GMS. ID comparisons are not a review of the
     * explanations. All case outputs are printed so that a person can review reasons and sources.
     * A fake-provider run checks the harness only and is not a model-quality result. A passing
     * real-provider test means the calls and safety checks ran; quality is measured in the report.
     */
    @Test
    void ownerRecommendationQualityAcrossSyntheticScenarios() throws Exception {
        var cases = List.of("FIELD_RENAME", "FIELD_RENAME_CLEAR", "DESCRIPTION", "EMPTY", "NO_SOURCE");
        var failures = new ArrayList<String>();
        for (int index = 0; index < cases.size(); index++) {
            if (index > 0) setup(); // independent synthetic database per case
            String name = cases.get(index);
            Map<String, Object> report = ownerEvaluation(name);
            System.out.println("OWNER_EVAL_JSON " + JsonMapper.builder().build().writeValueAsString(report));
            if ("FAILED".equals(report.get("analysis_status"))
                    || "NOT_EVALUATED".equals(report.get("analysis_status"))
                    || "STALE".equals(report.get("analysis_status"))) {
                failures.add(name + ": " + report.get("analysis_status") + " / " + report.get("error_code"));
            }
        }
        assertThat(failures).as("Cases with failed or unstarted analysis are not quality results").isEmpty();
    }

    private Map<String, Object> ownerEvaluation(String name) throws Exception {
        String before = "{\"method\":\"GET\",\"path\":\"/members\",\"response\":{\"userId\":\"string\"},\"description\":\"회원 조회\"}";
        long originalOtherVersion = api.currentVersionId("API-02");
        long newVersion = api.jdbc.sql("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                VALUES(:item,2,'APPROVED','회원 조회',1,cast(:content AS jsonb),'synthetic-before','HUMAN',now()) RETURNING id
                """).param("item", api.designItemId("API-01")).param("content", before).query(Long.class).single();
        baseline = api.jdbc.sql("INSERT INTO baseline(project_id,version_no,published_by) VALUES(:p,2,:a) RETURNING id")
                .param("p", projectId).param("a", api.accountId("leader@test")).query(Long.class).single();
        for (var entry : Map.of("API-01", newVersion, "API-02", originalOtherVersion).entrySet()) {
            api.jdbc.sql("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) VALUES(:b,:p,:i,:v)")
                    .param("b", baseline).param("p", projectId).param("i", api.designItemId(entry.getKey()))
                    .param("v", entry.getValue()).update();
        }
        api.jdbc.sql("UPDATE project SET current_baseline_id=:b WHERE id=:p").param("b", baseline).param("p", projectId).update();
        if (!"NO_SOURCE".equals(name)) ownerEvaluationSource("API-01");
        ownerEvaluationSource("API-02");

        var expectedModelTasks = new ArrayList<String>();
        var expectedFinalMembers = new LinkedHashSet<String>();
        String directMember = api.memberId("member@test").toString();
        String indirectMember = api.memberId("leader@test").toString();
        long directTask = 0;
        if (!"EMPTY".equals(name)) {
            directTask = ownerEvaluationTask("회원 userId 조회 화면", "API-01", member);
            expectedFinalMembers.add(directMember);
        }
        if (name.startsWith("FIELD_RENAME")) {
            String indirectTitle = "FIELD_RENAME_CLEAR".equals(name)
                    ? "회원 조회 API 응답 userId를 주문 화면에 표시" : "회원 userId 주문 화면";
            long indirectTask = ownerEvaluationTask(indirectTitle, "API-02", api.accountId("leader@test"));
            // A third owner and task are in the search results but explicitly unrelated to the changed field.
            api.jdbc.sql("INSERT INTO account(email,password_hash,display_name) SELECT 'other@test',password_hash,'다른 팀원' FROM account WHERE email='member@test'").update();
            long other = api.accountId("other@test");
            api.jdbc.sql("INSERT INTO project_member(project_id,account_id,role) VALUES(:project,:account,'MEMBER')")
                    .param("project", projectId).param("account", other).update();
            long unrelatedTask = ownerEvaluationTask("회원 화면 색상만 변경", "API-02", other);
            expectedModelTasks.add(Long.toString(directTask));
            expectedModelTasks.add(Long.toString(indirectTask));
            expectedFinalMembers.add(indirectMember);
            // The negative is only meaningful if it actually reaches the model input.
            assertThat(unrelatedTask).isPositive();
        }

        var after = new LinkedHashMap<String, Object>();
        after.put("method", "GET");
        after.put("path", "/members");
        after.put("response", Map.of("DESCRIPTION".equals(name) ? "userId" : "memberId", "string"));
        after.put("description", "DESCRIPTION".equals(name) ? "회원 정보 조회" : "회원 조회");
        var item = new ProposalDtos.Item("UPDATE", api.designItemId("API-01").toString(),
                api.currentVersionId("API-01").toString(), null, 1, after);
        String reason = switch (name) {
            case "FIELD_RENAME", "FIELD_RENAME_CLEAR" -> "회원 응답 식별자 변경";
            case "DESCRIPTION" -> "회원 조회 설명 문구 정리";
            case "EMPTY" -> "회원 응답 필드 변경";
            case "NO_SOURCE" -> "회원 응답 ID 변경";
            default -> throw new IllegalArgumentException("Unknown evaluation case");
        };
        var input = new ProposalDtos.Input(Long.toString(baseline), "가상 회원 API 변경",
                reason, null, null, List.of(item), List.of(), null, null, null);
        var created = proposals.create(CallerContext.human(member), projectId, input, "owner-eval-" + name);
        long proposalId = Long.parseLong(created.id());
        var snapshot = contexts.load(CallerContext.human(member), projectId, proposalId);
        assertThat(snapshot.searchCoverage().termsTruncated()).as(name + " search terms").isFalse();
        assertThat(snapshot.searchCoverage().tasksTruncated()).as(name + " search tasks").isFalse();
        if (name.startsWith("FIELD_RENAME")) {
            assertThat(snapshot.taskReferences().stream().map(ImpactContextQuery.TaskReference::taskId).distinct())
                    .as("positive and negative tasks passed to the AI").hasSize(3);
        }

        String path = "/api/projects/" + projectId + "/change-proposals/" + proposalId + "/impact";
        var started = api.postWithHeaders(path + "/retry", null, member, "Origin", "http://localhost:5173");
        assertThat(started.statusCode()).withFailMessage(started.body()).isEqualTo(202);
        Map<?, ?> response = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        do {
            var http = api.get(path, member);
            assertThat(http.statusCode()).withFailMessage(http.body()).isEqualTo(200);
            response = JsonMapper.builder().build().readValue(http.body(), Map.class);
            if (!"RUNNING".equals(response.get("analysis_status"))) break;
            Thread.sleep(200);
        } while (System.nanoTime() < deadline);
        assertThat(response.get("analysis_status")).as(name + " analysis deadline").isNotEqualTo("RUNNING");

        var owners = (Map<?, ?>) response.get("owner_recommendations");
        var recommendations = (List<?>) owners.get("recommendations");
        var modelTasks = new LinkedHashSet<String>();
        for (var raw : (List<?>) response.get("candidates")) {
            Object task = ((Map<?, ?>) raw).get("task_id");
            if (task instanceof String id) modelTasks.add(id);
        }
        var visibleAiTasks = new LinkedHashSet<String>();
        var finalMembers = new LinkedHashSet<String>();
        for (var raw : recommendations) {
            var owner = (Map<?, ?>) raw;
            finalMembers.add((String) owner.get("member_id"));
            for (var itemEvidence : (List<?>) owner.get("evidence")) {
                var evidence = (Map<?, ?>) itemEvidence;
                if ("AI_CANDIDATE".equals(evidence.get("type"))) visibleAiTasks.add((String) evidence.get("task_id"));
            }
        }
        Set<String> expectedNotification = "EMPTY".equals(name) ? Set.of() : Set.of(directMember);
        assertThat(new LinkedHashSet<>((List<?>) owners.get("notification_candidate_member_ids")))
                .as(name + " direct notification handoff").isEqualTo(expectedNotification);
        if ("EMPTY".equals(name) || "NO_SOURCE".equals(name)) {
            // Incomplete evidence must not turn AI candidates into owner recommendations.
            assertThat(visibleAiTasks).isEmpty();
            if (!Set.of("FAILED", "NOT_EVALUATED", "STALE").contains(response.get("analysis_status"))) {
                assertThat(response.get("analysis_status")).as(name + " evidence status")
                        .isEqualTo("INSUFFICIENT_EVIDENCE");
            }
        }

        var missingModelTasks = new LinkedHashSet<>(expectedModelTasks);
        missingModelTasks.removeAll(modelTasks);
        var unexpectedModelTasks = new LinkedHashSet<>(modelTasks);
        unexpectedModelTasks.removeAll(expectedModelTasks);
        var missingFinalMembers = new LinkedHashSet<>(expectedFinalMembers);
        missingFinalMembers.removeAll(finalMembers);
        var unexpectedFinalMembers = new LinkedHashSet<>(finalMembers);
        unexpectedFinalMembers.removeAll(expectedFinalMembers);
        if (!REAL_GMS) {
            assertThat(response.get("analysis_status")).as(name + " fake fixture evidence status")
                    .isEqualTo("EMPTY".equals(name) || "NO_SOURCE".equals(name)
                            ? "INSUFFICIENT_EVIDENCE" : "COMPLETED");
            if (!"NO_SOURCE".equals(name)) {
                assertThat(missingModelTasks).as(name + " fake fixture missing model tasks").isEmpty();
                assertThat(unexpectedModelTasks).as(name + " fake fixture unexpected model tasks").isEmpty();
            }
            assertThat(missingFinalMembers).as(name + " fake fixture missing final members").isEmpty();
            assertThat(unexpectedFinalMembers).as(name + " fake fixture unexpected final members").isEmpty();
        }

        var report = new LinkedHashMap<String, Object>();
        report.put("case", name);
        report.put("provider", REAL_GMS ? "GMS_REAL" : "FAKE_HARNESS_ONLY");
        report.put("analysis_status", response.get("analysis_status"));
        report.put("error_code", response.get("error_code"));
        report.put("expected_model_task_ids", "NO_SOURCE".equals(name) ? null : expectedModelTasks);
        report.put("actual_model_task_ids", modelTasks);
        report.put("missing_model_task_ids", "NO_SOURCE".equals(name) ? null : missingModelTasks);
        report.put("unexpected_model_task_ids", "NO_SOURCE".equals(name) ? null : unexpectedModelTasks);
        report.put("visible_ai_task_ids", visibleAiTasks);
        report.put("expected_final_member_ids", expectedFinalMembers);
        report.put("actual_final_member_ids", finalMembers);
        report.put("missing_final_member_ids", missingFinalMembers);
        report.put("unexpected_final_member_ids", unexpectedFinalMembers);
        report.put("withheld_ai_candidates", owners.get("withheld_ai_candidates"));
        report.put("model_candidates", response.get("candidates"));
        report.put("final_recommendations", recommendations);
        report.put("generation", response.get("generation"));
        report.put("human_review_required", "Check each reason and source against the synthetic case.");
        return report;
    }

    private long ownerEvaluationTask(String title, String item, long account) {
        var response = api.post("/api/projects/" + projectId + "/tasks", api.planJson(title, api.readsRef(item), "[]"), account);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return TaskApiSupport.longField(response.body(), "id");
    }

    private void ownerEvaluationSource(String item) {
        var existing = api.jdbc.sql("SELECT dv.id FROM document d JOIN document_version dv ON dv.document_id=d.id WHERE d.project_id=:p AND d.type='API_SPEC'")
                .param("p", projectId).query(Long.class).list();
        long version;
        if (existing.isEmpty()) {
            long document = api.jdbc.sql("INSERT INTO document(project_id,type,title,created_by) VALUES(:p,'API_SPEC','가상 API',:a) RETURNING id")
                    .param("p", projectId).param("a", member).query(Long.class).single();
            version = api.jdbc.sql("""
                    INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_at)
                    VALUES(:d,1,'APPROVED','synthetic-eval.md','synthetic-hash',:text,:a,now()) RETURNING id
                    """).param("d", document).param("a", member)
                    .param("text", "# API 계약\nGET /members 응답: userId (string).\nGET /orders 주문 조회.\n")
                    .query(Long.class).single();
            api.jdbc.sql("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES(:b,:p,:d,:v)")
                    .param("b", baseline).param("p", projectId).param("d", document).param("v", version).update();
        } else {
            version = existing.getFirst();
        }
        api.jdbc.sql("UPDATE design_item_version SET source_document_version_id=:v,source_locator=cast(:locator AS jsonb) WHERE id=:id")
                .param("v", version).param("locator", "{\"location\":\"API 계약\"}")
                .param("id", api.currentVersionId(item)).update();
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
