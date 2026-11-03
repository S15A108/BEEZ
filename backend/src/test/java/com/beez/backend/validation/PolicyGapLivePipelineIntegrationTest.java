package com.beez.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;

import com.beez.backend.ai.AiClient;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.worker.ValidationWorker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
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
import tools.jackson.databind.json.JsonMapper;

/** Opt-in synthetic PostgreSQL + real search, embedding and FastAPI/GMS validation. */
@EnabledIfEnvironmentVariable(named = "BEEZ_POLICY_LIVE_AI_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@TestPropertySource(properties = {"beez.validation.worker-enabled=false", "beez.validation.policy-analysis-enabled=true",
        "beez.validation.execution-budget-ms=60000", "beez.validation.provider=gms",
        "beez.document.embedding-profile=gms-small-1024-v1", "beez.document.embedding-model=text-embedding-3-small",
        "beez.ai.connect-timeout=5s", "beez.ai.read-timeout=75s"})
class PolicyGapLivePipelineIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DataSource dataSource;
    @Autowired ValidationWorker worker;
    @MockitoSpyBean AiClient ai;
    private TaskApiSupport api;
    private long project, owner, baseline;
    private Map<String, Object> sent;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("beez.ai.base-url", () -> System.getenv("BEEZ_POLICY_LIVE_AI_URL"));
        registry.add("beez.ai.internal-token", () -> Objects.requireNonNull(System.getenv("BEEZ_POLICY_LIVE_AI_TOKEN")));
    }

    @BeforeEach
    void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId(); owner = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p").param("p", project).query(Long.class).single();
        sent = null;
        doAnswer(call -> {
            sent = call.getArgument(0);
            return call.callRealMethod();
        }).when(ai).validate(anyMap());
    }

    @Test
    void searchesSyntheticPolicyWithRealEmbeddingsAndAppliesRealModelAssessment() throws Exception {
        String text = "로그인 5회 실패하면 계정을 30분 잠근다.\n";
        Map<String, Object> embedding = ai.documentEmbeddings(Map.of("project_id", Long.toString(project),
                "request_key", "policy-live-document-" + project, "embedding_profile_id", "gms-small-1024-v1",
                "purpose", "DOCUMENT", "model", "text-embedding-3-small", "dimension", 1024,
                "texts", List.of(Map.of("key", "doc", "text", text))));
        var embeddingResult = map(embedding.get("result"));
        assertThat(embeddingResult.get("model")).isEqualTo("text-embedding-3-small");
        List<?> vectors = (List<?>) embeddingResult.get("vectors");
        var vector = (List<?>) map(vectors.getFirst()).get("embedding");
        assertThat(vector).hasSize(1024);
        String hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        long document = api.jdbc.sql("insert into document(project_id,type,title,created_by) values(:p,'API_SPEC','로그인 잠금 정책',:a) returning id")
                .param("p", project).param("a", owner).query(Long.class).single();
        long docVersion = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,parse_status,storage_key,content_hash,content_text,created_by,approved_at)
                values(:d,1,'APPROVED','OK','synthetic-policy.md',:h,:text,:a,now()) returning id
                """).param("d", document).param("h", hash).param("text", text).param("a", owner).query(Long.class).single();
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values(:b,:p,:d,:v)")
                .param("b", baseline).param("p", project).param("d", document).param("v", docVersion).update();
        api.jdbc.sql("""
                insert into document_processing(document_version_id,attempt,status,source_status,index_status,text_hash)
                values(:v,1,'COMPLETED','READY','READY',:h)
                """).param("v", docVersion).param("h", hash).update();
        api.jdbc.sql("""
                insert into document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                values(:v,1,:h,'gms-small-1024-v1','synthetic','synthetic',1)
                """).param("v", docVersion).param("h", hash).update();
        api.jdbc.sql("""
                insert into document_chunk(document_version_id,chunk_no,text,locator,embedding)
                values(:v,0,:text,'{"start_line":1,"end_line":1}'::jsonb,cast(:vector as vector))
                """).param("v", docVersion).param("text", text).param("vector", JSON.writeValueAsString(vector)).update();
        long item = api.jdbc.sql("insert into design_item(project_id,key,kind) values(:p,'POLICY-LIVE-186','POLICY') returning id")
                .param("p", project).query(Long.class).single();
        long version = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,
                    origin,approved_at,source_document_version_id,source_locator)
                values(:i,1,'APPROVED','로그인 잠금 정책',1,cast(:content as jsonb),'synthetic-policy-hash','HUMAN',now(),:doc,
                    '{"location":"1행","type":"text_lines","start_line":1,"end_line":1}'::jsonb) returning id
                """).param("i", item).param("content", JSON.writeValueAsString(Map.of("description", text.strip())))
                .param("doc", docVersion).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values(:b,:p,:i,:v)")
                .param("b", baseline).param("p", project).param("i", item).param("v", version).update();

        String plan = """
                {"title":"로그인 잠금 정책","goal":"로그인 5회 실패하면 계정을 30분 잠근다.",
                 "included_scope":["로그인 5회 실패하면 계정을 30분 잠근다."],"excluded_scope":[],
                 "approach":"로그인 5회 실패하면 계정을 30분 잠근다.",
                 "completion_criteria":["로그인 5회 실패하면 계정을 30분 잠근다."],
                 "dependency_task_ids":[],"design_refs":[],"unlinked":[],"no_contract_change":true}
                """;
        var task = api.post("/api/projects/" + project + "/tasks", plan, owner);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(task.body(), "id");
        long revision = api.jdbc.sql("select current_plan_revision_id from task where id=:t").param("t", taskId).query(Long.class).single();
        var accepted = api.post("/api/projects/" + project + "/tasks/" + taskId + "/validations",
                "{\"plan_revision_id\":\"" + revision + "\",\"expected_lock_version\":" + TaskApiSupport.longField(task.body(), "lock_version") + "}", owner);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long validation = TaskApiSupport.longField(accepted.body(), "id");
        assertThat(worker.runOnce()).isTrue();
        var observed = api.get("/api/projects/" + project + "/validations/" + validation, owner);
        var result = JSON.readTree(observed.body());
        assertThat(result.get("status").asText()).as(result.toString()).isEqualTo("PASS");
        assertThat(result.get("policy_analysis").get("policy_assessments").toString()).contains("SUPPORTED");
        assertThat(sent).isNotNull();
        assertThat(sent.get("approved_items").toString()).contains("POLICY-LIVE-186");
        assertThat(sent.get("evidence").toString()).contains(Long.toString(version));
        assertThat(api.jdbc.sql("select count(*) from validation_design_ref where validation_id=:v")
                .param("v", validation).query(Integer.class).single()).isZero();
        assertThat(api.jdbc.sql("select design_item_version_id from validation_policy_ref where validation_id=:v")
                .param("v", validation).query(Long.class).list()).contains(version);
        assertThat(api.jdbc.sql("select count(*) from llm_usage where project_id=:p and feature='EMBEDDING' and failed=false")
                .param("p", project).query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(api.jdbc.sql("select usage::text from validation_run where validation_id=:v")
                .param("v", validation).query(String.class).single()).contains("gpt-6-luna");
    }

    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
