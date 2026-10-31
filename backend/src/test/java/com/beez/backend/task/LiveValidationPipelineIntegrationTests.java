package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;

import com.beez.backend.ai.AiClient;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.validation.service.ValidationWorker;
import com.beez.backend.validation.service.ValidationInputAssembler;
import com.beez.backend.validation.service.ValidationRuleOutcomeWriter;
import com.beez.backend.common.api.ApiException;
import java.sql.SQLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Opt-in, billable integration test: isolated Testcontainers PostgreSQL + real AI HTTP.
 * Query embeddings are synthetic; pgvector query and persistence remain real. The
 * HTTP endpoint must be a separately started AI server using an authorized provider.
 * Only synthetic fixtures are sent. Never point it at a shared production service.
 */
@EnabledIfEnvironmentVariable(named = "BEEZ_VALIDATION_LIVE_AI_URL", matches = "https?://.+")
@TestPropertySource(properties = {"beez.validation.worker-enabled=false",
        "beez.document.embedding-profile=synthetic-profile", "beez.document.embedding-model=synthetic-model",
        "beez.ai.connect-timeout=5s", "beez.ai.read-timeout=60s"})
class LiveValidationPipelineIntegrationTests extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @MockitoSpyBean AiClient ai;
    @MockitoSpyBean ValidationInputAssembler assembler;
    @MockitoSpyBean ValidationRuleOutcomeWriter ruleWriter;
    private TaskApiSupport api;
    private long project, owner, design, version, documentVersion;
    private Map<String, Object> sentRequest;

    @DynamicPropertySource
    static void aiConfiguration(DynamicPropertyRegistry registry) {
        // Provider is an explicit operator assertion: the test must configure the real endpoint accordingly.
        registry.add("beez.validation.provider", () -> System.getenv().getOrDefault("BEEZ_VALIDATION_EXPECTED_PROVIDER", "openai"));
        registry.add("beez.ai.base-url", () -> System.getenv("BEEZ_VALIDATION_LIVE_AI_URL"));
        registry.add("beez.ai.internal-token", () -> Objects.requireNonNull(
                System.getenv("BEEZ_VALIDATION_LIVE_AI_TOKEN"), "BEEZ_VALIDATION_LIVE_AI_TOKEN is required"));
    }

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        owner = api.accountId("member@test");
        sentRequest = null;
        // The LLM validation call remains real. The embedding is a deterministic
        // test vector, so this test does not claim to validate the embedding model.
        doAnswer(inv -> {
            var vector = new ArrayList<Double>();
            vector.add(1.0);
            for (int i = 1; i < 1024; i++) vector.add(0.0);
            return Map.of("origin", "MODEL", "usage", List.of(), "result", Map.of(
                    "embedding_profile_id", "synthetic-profile", "purpose", "QUERY",
                    "model", "synthetic-model", "dimension", 1024,
                    "vectors", List.of(Map.of("key", "query", "embedding", vector))));
        }).when(ai).documentEmbeddings(anyMap());
        doAnswer(inv -> {
            @SuppressWarnings("unchecked") Map<String, Object> input = inv.getArgument(0, Map.class);
            sentRequest = new LinkedHashMap<>(input);
            if ("true".equalsIgnoreCase(System.getenv("BEEZ_VALIDATION_SAVE_REQUEST"))) {
                Path report = Path.of("build", "reports", "validation-request.json");
                Files.createDirectories(report.getParent());
                Files.writeString(report, JSON.writeValueAsString(input));
            }
            if ("true".equalsIgnoreCase(System.getenv("BEEZ_VALIDATION_STOP_BEFORE_MODEL"))) {
                throw new IllegalStateException("synthetic stop before model");
            }
            try { return inv.callRealMethod(); }
            catch (RestClientResponseException error) {
                String code = "unknown";
                try {
                    Map<String, Object> payload = JSON.readValue(error.getResponseBodyAsString(),
                            new TypeReference<Map<String, Object>>() {});
                    if (payload.get("error") instanceof Map<?, ?> details && details.get("code") instanceof String value
                            && value.matches("[A-Za-z0-9_-]{1,80}")) code = value;
                } catch (RuntimeException ignored) { /* Never print untrusted response bodies. */ }
                System.out.printf("synthetic diagnostic: stage=ai_http status=%d code=%s%n", error.getStatusCode().value(), code);
                throw error;
            } catch (RuntimeException error) {
                diagnostic("ai_http", error);
                throw error;
            }
        }).when(ai).validatePlan(anyMap());
        doAnswer(inv -> {
            try { return inv.callRealMethod(); }
            catch (RuntimeException error) { diagnostic("assembler", error); throw error; }
        }).when(assembler).assemble(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        doAnswer(inv -> {
            try { return inv.callRealMethod(); }
            catch (RuntimeException error) { diagnostic("rules", error); throw error; }
        }).when(ruleWriter).apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private static void diagnostic(String stage, Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String detail = current instanceof SQLException sql ? " sqlstate=" + sql.getSQLState()
                    : current instanceof ApiException apiError ? " code=" + apiError.getCode() : "";
            System.out.printf("synthetic diagnostic: stage=%s type=%s%s%n", stage, current.getClass().getSimpleName(), detail);
        }
    }

    private void seed(boolean conflictingDocument) {
        long baseline = api.jdbc.sql("select current_baseline_id from project where id=:project")
                .param("project", project).query(Long.class).single();
        long doc = api.jdbc.sql("insert into document(project_id,type,title,created_by) values(:project,'API_SPEC','회원 조회 API',:owner) returning id")
                .param("project", project).param("owner", owner).query(Long.class).single();
        String field = conflictingDocument ? "userId" : "memberId";
        String text = "# 회원 조회 API\nGET /members/{" + field + "}\n요청 필드: " + field + "\n응답 필드: memberId\n";
        String textHash;
        try { textHash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        documentVersion = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,parse_status,storage_key,content_hash,content_text,created_by,approved_at)
                values(:doc,1,'APPROVED','OK','synthetic.md',:hash,:text,:owner,now()) returning id
                """).param("doc", doc).param("text", text).param("hash", textHash).param("owner", owner).query(Long.class).single();
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values(:baseline,:project,:doc,:version)")
                .param("baseline", baseline).param("project", project).param("doc", doc).param("version", documentVersion).update();
        api.jdbc.sql("""
                insert into document_processing(document_version_id,attempt,status,source_status,index_status,text_hash)
                values(:version,1,'COMPLETED','READY','READY',:hash)
                """).param("version", documentVersion).param("hash", textHash).update();
        api.jdbc.sql("""
                insert into document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                values(:version,1,:hash,'synthetic-profile','synthetic','synthetic',1)
                """).param("version", documentVersion).param("hash", textHash).update();
        String vector = "[1," + "0,".repeat(1022) + "0]";
        api.jdbc.sql("""
                insert into document_chunk(document_version_id,chunk_no,text,locator,embedding)
                values(:version,0,:text,'{"start_line":1,"end_line":4}'::jsonb,cast(:vector as vector))
                """).param("version", documentVersion).param("text", text).param("vector", vector).update();
        design = api.jdbc.sql("insert into design_item(project_id,key,kind) values(:project,'API-184','API_CONTRACT') returning id")
                .param("project", project).query(Long.class).single();
        String content = """
                {"description":"회원 조회 API","method":"GET","path":"/members/{memberId}",
                 "request_fields":[{"name":"memberId","location":"path","type":"string","required":true}],
                 "responses":[{"status_code":"200","fields":[{"name":"memberId","type":"string","required":true}]}]}
                """;
        version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,
                  origin,approved_at,source_document_version_id,source_locator)
                values(:item,1,'APPROVED','회원 조회 API',1,cast(:content as jsonb),'synthetic-hash','HUMAN',now(),:doc,
                  '{"location":"회원 조회 API","type":"text_lines","start_line":1,"end_line":4}'::jsonb) returning id
                """).param("item", design).param("content", content).param("doc", documentVersion).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values(:baseline,:project,:item,:version)")
                .param("baseline", baseline).param("project", project).param("item", design).param("version", version).update();
    }

    private Map<String, Object> execute(String identifier) {
        String plan = """
                {"title":"회원 조회 API","goal":"회원 조회","included_scope":["회원 조회"],"excluded_scope":[],
                 "approach":"GET /members/{%s}","completion_criteria":["회원 정보 조회 성공"],"dependency_task_ids":[],
                 "design_refs":[{"design_item_id":"%d","design_item_version_id":"%d","relation":"IMPLEMENTS"}],
                 "unlinked":[],"no_contract_change":true}
                """.formatted(identifier, design, version);
        var http = client().login("member@test", AccessFixtures.PASSWORD);
        var created = http.post("/api/projects/" + project + "/tasks", plan);
        assertThat(created.statusCode()).isEqualTo(201);
        long task = TaskApiSupport.longField(created.body(), "id");
        var row = api.jdbc.sql("select current_plan_revision_id,lock_version from task where id=:id")
                .param("id", task).query().singleRow();
        var accepted = http.post("/api/projects/" + project + "/tasks/" + task + "/validations",
                "{\"plan_revision_id\":\"" + row.get("current_plan_revision_id") + "\",\"expected_lock_version\":" + row.get("lock_version") + "}",
                "Idempotency-Key", "live-" + task);
        assertThat(accepted.statusCode()).isEqualTo(202);
        long validation = TaskApiSupport.longField(accepted.body(), "id");
        assertThat(worker.runOnce()).isTrue();
        var observed = http.get("/api/projects/" + project + "/validations/" + validation);
        assertThat(observed.statusCode()).isEqualTo(200);
        Map<String, Object> result = JSON.readValue(observed.body(), new TypeReference<Map<String, Object>>() {});
        long usage = api.jdbc.sql("select count(*) from llm_usage u join validation_run r on r.id=u.validation_run_id where r.validation_id=:validation and u.feature='TASK_VALIDATION'")
                .param("validation", validation).query(Long.class).single();
        var runRows = api.jdbc.sql("select status,error from validation_run where validation_id=:validation order by attempt desc")
                .param("validation", validation).query().listOfRows();
        System.out.printf("synthetic validation: status=%s error_code=%s findings=%s run=%s ai_request_reached=%s task_validation_usage=%d%n",
                result.get("status"), result.get("error_code"), result.get("findings"), runRows, sentRequest != null, usage);
        assertThat(usage).as("real model usage must be persisted").isEqualTo(1);
        String expectedProvider = System.getenv().getOrDefault("BEEZ_VALIDATION_EXPECTED_PROVIDER", "openai");
        var usageRow = api.jdbc.sql("""
                select u.provider,u.model,u.input_tokens,u.output_tokens,u.failed
                from llm_usage u join validation_run r on r.id=u.validation_run_id
                where r.validation_id=:validation and u.feature='TASK_VALIDATION'
                """).param("validation", validation).query().singleRow();
        String storedProvider = Objects.toString(usageRow.get("provider"), "");
        String model = Objects.toString(usageRow.get("model"), "");
        // The report contains only provider/model and numeric usage for synthetic cases;
        // redact an unexpected model or provider instead of printing arbitrary DB text.
        String safeProvider = List.of("gms", "openai").contains(storedProvider) ? storedProvider : "[unexpected]";
        String safeModel = model.matches("(?i)(gpt-[a-z0-9][a-z0-9._-]{0,80}|o[0-9][a-z0-9._-]{0,80})")
                ? model : "[unexpected]";
        System.out.printf("synthetic llm_usage: provider=%s model=%s input_tokens=%s output_tokens=%s failed=%s%n",
                safeProvider, safeModel, usageRow.get("input_tokens"), usageRow.get("output_tokens"), usageRow.get("failed"));
        result.put("_test_provider", storedProvider);
        result.put("_test_expected_provider", expectedProvider);
        assertThat(sentRequest).as("real AI validation route must be called").isNotNull();
        return result;
    }

    @Test
    void normalPlanShouldPassWithinDeclaredScope() {
        seed(false);
        var result = execute("memberId");
        assertSoftly(check -> {
            check.assertThat(result.get("status")).as("normal plan decision").isEqualTo("PASS");
            check.assertThat(result.get("_test_provider")).as("actual model provider in usage").isEqualTo(result.get("_test_expected_provider"));
        });
    }

    @Test
    void mismatchedIdentifierShouldNeedReview() {
        seed(false);
        var result = execute("userId");
        assertSoftly(check -> {
            check.assertThat(result.get("status")).as("mismatched plan decision").isEqualTo("NEEDS_REVIEW");
            check.assertThat(result.get("findings").toString()).contains("MISMATCH");
            check.assertThat(result.get("_test_provider")).as("actual model provider in usage").isEqualTo(result.get("_test_expected_provider"));
        });
    }

    @Test
    void unresolvedPolicyMustNotPass() {
        seed(false);
        api.jdbc.sql("insert into planning_question(project_id,design_item_id,kind,question,origin,status) values(:project,:item,'CLARIFICATION','회원 조회 권한 정책은 미정이다','human','OPEN')")
                .param("project", project).param("item", design).update();
        var result = execute("memberId");
        assertSoftly(check -> {
            check.assertThat(result.get("status")).as("unresolved policy decision").isEqualTo("ERROR");
            check.assertThat(result.get("findings").toString()).contains("HUMAN_REVIEW");
            check.assertThat(result.get("_test_provider")).as("actual model provider in usage").isEqualTo(result.get("_test_expected_provider"));
        });
    }

    @Test
    void conflictingSourceMustReachAiAsVerifiedEvidence() {
        seed(true);
        var result = execute("memberId");
        assertSoftly(check -> {
            check.assertThat(sentRequest.get("evidence").toString()).as("source conflict must be provided as verified evidence").isNotEqualTo("[]");
            check.assertThat(result.get("status")).as("conflicting source decision").isEqualTo("NEEDS_REVIEW");
            check.assertThat(result.get("_test_provider")).as("actual model provider in usage").isEqualTo(result.get("_test_expected_provider"));
        });
    }
}
