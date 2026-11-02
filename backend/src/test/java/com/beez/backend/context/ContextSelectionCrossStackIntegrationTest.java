package com.beez.backend.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.relation.dto.RelationAction;
import com.beez.backend.relation.dto.RelationChangeRequest;
import com.beez.backend.relation.dto.RelationCommand;
import com.beez.backend.relation.service.RelationStorageService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synthetic cross-boundary experiment, not production BE orchestration or MCP E2E.
 * A test-only assembler checks source and relation history against its isolated DB.
 */
@EnabledIfEnvironmentVariable(named = "BEEZ_131_CROSS_STACK", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"beez.document.worker-enabled=false", "beez.document.embedding-profile=test-profile",
                "beez.document.embedding-model=test-model"})
@Import(ContextSelectionCrossStackIntegrationTest.Support.class)
class ContextSelectionCrossStackIntegrationTest extends IntegrationTestSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SOURCE = "GET /users 는 인증된 사용자만 조회한다.\n";

    @Autowired DataSource dataSource;
    @Autowired AiClient ai;
    @Autowired RelationStorageService relations;
    @Autowired PlatformTransactionManager transactions;
    TaskApiSupport api;
    long project, member, leader, baseline, docVersion, sourceItem, sourceVersion, targetItem, targetVersion;

    @TestConfiguration
    static class Support {
        @Bean @Primary AiClient testAi(BeezProperties _properties) { return mock(AiClient.class); }
    }

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        baseline = api.jdbc.sql("select current_baseline_id from project where id=:p")
                .param("p", project).query(Long.class).single();
        sourceItem = api.designItemId("API-01");
        sourceVersion = api.currentVersionId("API-01");
        targetItem = api.jdbc.sql("insert into design_item(project_id,key,kind) values (:p,'POL-CROSS','POLICY') returning id")
                .param("p", project).query(Long.class).single();
        targetVersion = api.jdbc.sql("""
                insert into design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,approved_at)
                values (:i,1,'APPROVED','인증 정책',1,'{"policy":"authentication"}','cross-policy-hash','HUMAN',now()) returning id
                """).param("i", targetItem).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) values (:b,:p,:i,:v)")
                .params(Map.of("b", baseline, "p", project, "i", targetItem, "v", targetVersion)).update();
        new TransactionTemplate(transactions).executeWithoutResult(status -> relations.apply(
                CallerContext.human(leader), project,
                new RelationChangeRequest(baseline, null, List.of(), null,
                        List.of(new RelationCommand(RelationAction.CONFIRM, sourceItem, targetItem, "synthetic")))));

        long doc = api.jdbc.sql("insert into document(project_id,type,title,created_by) values (:p,'REQ_SPEC','합성 문서',:a) returning id")
                .params(Map.of("p", project, "a", leader)).query(Long.class).single();
        docVersion = api.jdbc.sql("""
                insert into document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_at)
                values (:d,1,'APPROVED','fixture.md','fixture-hash',:text,:a,now()) returning id
                """).params(Map.of("d", doc, "text", SOURCE, "a", leader)).query(Long.class).single();
        api.jdbc.sql("insert into baseline_document(baseline_id,project_id,document_id,document_version_id) values (:b,:p,:d,:v)")
                .params(Map.of("b", baseline, "p", project, "d", doc, "v", docVersion)).update();
        api.jdbc.sql("""
                insert into document_processing(document_version_id,attempt,status,source_status,index_status,text_hash)
                values (:v,1,'COMPLETED','READY','READY','fixture-text-hash')
                """).param("v", docVersion).update();
        api.jdbc.sql("""
                insert into document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                values (:v,1,'fixture-text-hash','test-profile','fixture','fixture',1)
                """).param("v", docVersion).update();
        String locator = "{\"type\":\"text_lines\",\"start_line\":1,\"end_line\":1}";
        String spans = "[{\"block_id\":\"fixture\",\"start_offset\":0,\"end_offset\":"
                + SOURCE.codePointCount(0, SOURCE.length()) + ",\"locator\":" + locator + "}]";
        api.jdbc.sql("""
                insert into document_chunk(document_version_id,chunk_no,text,locator,chunk_key,source_spans,context_refs,embedding)
                values (:v,0,:text,cast(:loc as jsonb),'fixture-chunk',cast(:spans as jsonb),'[]'::jsonb,
                  (ARRAY[1.0]::double precision[] || array_fill(0.0::double precision,ARRAY[1023]))::vector)
                """).params(Map.of("v", docVersion, "text", SOURCE, "loc", locator, "spans", spans)).update();

        when(ai.documentEmbeddings(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            var vector = new ArrayList<Double>(Collections.nCopies(1024, 0.0));
            vector.set(0, 1.0);
            return Map.of("origin", "MODEL", "usage", List.of(), "result", Map.of(
                    "model", "test-model", "dimension", 1024, "embedding_profile_id", "test-profile",
                    "purpose", "QUERY", "vectors", List.of(Map.of("key", "query", "embedding", vector))));
        });
    }

    @Test
    void actualBeSearchAndRelationToActualAiRoute_withExplicitFixtureChecks() throws Exception {
        String basePath = "/api/projects/" + project;
        assertThat(api.get(basePath + "/search?q=users&mode=SEMANTIC", api.accountId("outsider@test")).statusCode())
                .isEqualTo(404);
        JsonNode before = get(basePath + "/baselines/current");
        assertThat(before.get("id").asText()).isEqualTo(Long.toString(baseline));
        assertThat(before.get("project_id").asText()).isEqualTo(Long.toString(project));
        assertThat(before.get("documents").size()).isEqualTo(1);
        assertThat(before.get("documents").get(0).get("document_version_id").asText()).isEqualTo(Long.toString(docVersion));
        JsonNode search = get(basePath + "/search?q=users&mode=SEMANTIC&limit=10");
        assertThat(search.get("has_more").asBoolean()).isFalse();
        assertThat(search.get("items").size()).isEqualTo(1);
        JsonNode hit = search.get("items").get(0);
        assertThat(hit.get("type").asText()).isEqualTo("DOCUMENT_CHUNK");
        assertThat(hit.get("source").get("document_version_id").asText()).isEqualTo(Long.toString(docVersion));
        assertThat(hit.get("chunk_key").asText()).isEqualTo("fixture-chunk");
        assertThat(hit.get("snippet").asText()).isEqualTo(SOURCE);
        assertThat(hit.get("source_spans").size()).isEqualTo(1);
        JsonNode span = hit.get("source_spans").get(0);
        int[] codePoints = SOURCE.codePoints().toArray();
        assertThat(new String(codePoints, span.get("start_offset").asInt(),
                span.get("end_offset").asInt() - span.get("start_offset").asInt())).isEqualTo(hit.get("snippet").asText());

        JsonNode dependencies = get(basePath + "/design-items/" + sourceItem + "/dependencies");
        assertThat(dependencies.get("items").size()).isEqualTo(1);
        JsonNode dependency = dependencies.get("items").get(0);
        assertThat(dependency.get("from_design_item_id").asText()).isEqualTo(Long.toString(sourceItem));
        assertThat(dependency.get("to_design_item_id").asText()).isEqualTo(Long.toString(targetItem));
        assertThat(dependency.get("relation").asText()).isEqualTo("DEPENDS_ON");
        assertThat(dependency.get("origin").asText()).isEqualTo("EXPLICIT");
        assertThat(dependency.get("status").asText()).isEqualTo("CONFIRMED");
        JsonNode history = get(basePath + "/design-items/" + sourceItem + "/dependencies/history?limit=20");
        assertThat(history.get("items").size()).isEqualTo(1);
        JsonNode confirmed = history.get("items").get(0);
        assertThat(confirmed.get("baseline_id").asText()).isEqualTo(Long.toString(baseline));
        assertThat(confirmed.get("from_design_item_version_id").asText()).isEqualTo(Long.toString(sourceVersion));
        assertThat(confirmed.get("to_design_item_version_id").asText()).isEqualTo(Long.toString(targetVersion));
        assertThat(confirmed.get("relation_set_revision").asLong()).isEqualTo(dependencies.get("relation_set_revision").asLong());

        HttpResponse<String> createdTask = api.post(basePath + "/tasks",
                api.planJson("합성 작업", api.readsRef("API-02"), "[]"), member);
        assertThat(createdTask.statusCode()).isEqualTo(201);
        long taskId = api.jdbc.sql("select id from task where project_id=:p and key='T-1'")
                .param("p", project).query(Long.class).single();
        long taskItem = api.designItemId("API-02");
        long taskVersion = api.currentVersionId("API-02");
        JsonNode taskBefore = get(basePath + "/tasks/" + taskId);
        String taskRevision = taskBefore.get("current_plan_revision").get("id").asText();
        JsonNode taskRelations = get(basePath + "/relations?task_id=" + taskId);
        assertThat(taskRelations.get("edges").size()).isEqualTo(1);
        JsonNode taskEdge = taskRelations.get("edges").get(0);
        assertThat(taskEdge.get("from_type").asText()).isEqualTo("TASK");
        assertThat(taskEdge.get("from_id").asText()).isEqualTo(Long.toString(taskId));
        assertThat(taskEdge.get("to_type").asText()).isEqualTo("DESIGN_ITEM");
        assertThat(taskEdge.get("to_id").asText()).isEqualTo(Long.toString(taskItem));
        assertThat(taskEdge.get("design_item_version_id").asText()).isEqualTo(Long.toString(taskVersion));

        JsonNode approved = get(basePath + "/design-items?status=APPROVED&limit=100");
        assertThat(approved.get("has_more").asBoolean()).isFalse();
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode item : approved.get("items")) {
            assertThat(item.get("status").asText()).isEqualTo("APPROVED");
            String itemId = item.get("design_item_id").asText();
            String versionId = item.get("id").asText();
            boolean inBaseline = false;
            for (JsonNode ref : before.get("items")) {
                if (itemId.equals(ref.get("design_item_id").asText())
                        && versionId.equals(ref.get("design_item_version_id").asText())) inBaseline = true;
            }
            assertThat(inBaseline).isTrue();
            items.add(Map.of("design_item_id", itemId, "design_item_version_id", versionId,
                    "project_id", Long.toString(project), "baseline_id", Long.toString(baseline),
                    "status", "APPROVED", "text", JSON.writeValueAsString(item.get("content"))));
        }
        assertThat(items).hasSize(3);

        // The following mapping is an explicit TEST-ONLY assumption, not a BE/AI contract.
        Map<String, Object> payload = new HashMap<>();
        payload.put("request_key", "cross-stack-synthetic");
        payload.put("project_id", Long.toString(project));
        payload.put("baseline_id", Long.toString(baseline));
        payload.put("request", "사용자 /users 구현");
        payload.put("task_id", Long.toString(taskId));
        payload.put("document_version_ids", List.of(Long.toString(docVersion)));
        payload.put("items", items);
        payload.put("evidence", List.of(Map.of("evidence_id", hit.get("id").asText(),
                "document_version_id", Long.toString(docVersion), "project_id", Long.toString(project),
                "baseline_id", Long.toString(baseline), "text", hit.get("snippet").asText())));
        payload.put("relations", List.of(Map.of("from_design_item_id", Long.toString(sourceItem),
                "from_design_item_version_id", Long.toString(sourceVersion), "to_design_item_id", Long.toString(targetItem),
                "to_design_item_version_id", Long.toString(targetVersion), "relation", dependency.get("relation").asText(),
                "origin", dependency.get("origin").asText(), "status", dependency.get("status").asText())));
        payload.put("task_design_refs", List.of(Map.of("design_item_id", Long.toString(taskItem),
                "design_item_version_id", Long.toString(taskVersion))));
        payload.put("search_status", "COMPLETE");
        assertThat(get(basePath + "/baselines/current").get("id").asText()).isEqualTo(Long.toString(baseline));
        assertThat(get(basePath + "/design-items/" + sourceItem + "/dependencies").get("relation_set_revision").asLong())
                .isEqualTo(dependencies.get("relation_set_revision").asLong());
        assertThat(get(basePath + "/tasks/" + taskId).get("current_plan_revision").get("id").asText())
                .isEqualTo(taskRevision);

        JsonNode response = callAiProbe(payload);
        assertThat(response.get("status").asInt()).isEqualTo(200);
        JsonNode result = response.get("body").get("result");
        assertThat(result.get("evidence_ids").get(0).asText()).isEqualTo(hit.get("id").asText());
        Map<String, String> selected = new HashMap<>();
        for (JsonNode item : result.get("design_items")) selected.put(item.get("design_item_version_id").asText(), item.get("reasons").get(0).asText());
        assertThat(selected).containsEntry(Long.toString(sourceVersion), "MATCHED")
                .containsEntry(Long.toString(targetVersion), "DEPENDENCY")
                .containsEntry(Long.toString(taskVersion), "TASK_PLAN").hasSize(3);
        assertThat(result.get("unresolved").size()).isZero();
        assertThat(response.get("body").get("usage").size()).isEqualTo(2);
        assertThat(response.toString()).doesNotContain("GET /users").doesNotContain("snapshot_binding")
                .doesNotContain("cross-stack-fixture-token");
        HttpResponse<String> authorizedAfter = api.get(basePath + "/baselines/current", member);
        assertThat(authorizedAfter.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(authorizedAfter.body()).get("id").asText()).isEqualTo(Long.toString(baseline));
        assertThat(get(basePath + "/design-items/" + sourceItem + "/dependencies").get("relation_set_revision").asLong())
                .isEqualTo(dependencies.get("relation_set_revision").asLong());
        assertThat(get(basePath + "/tasks/" + taskId).get("current_plan_revision").get("id").asText())
                .isEqualTo(taskRevision);

        // A failed BE index is not an empty successful retrieval. The test-only
        // assembler reports NOT_READY rather than fabricating a zero-hit success.
        api.jdbc.sql("update document_processing set status='FAILED',index_status='FAILED' where document_version_id=:v")
                .param("v", docVersion).update();
        HttpResponse<String> notReady = api.get(basePath + "/search?q=users&mode=SEMANTIC&limit=10", member);
        assertThat(notReady.statusCode()).isEqualTo(409);
        assertThat(notReady.body()).contains("index_not_ready");
        payload.put("search_status", "NOT_READY");
        JsonNode failed = callAiProbe(payload);
        assertThat(failed.get("status").asInt()).isEqualTo(503);
        assertThat(failed.get("body").get("error").get("code").asText()).isEqualTo("context_not_ready");
        assertThat(failed.get("body").has("usage")).isFalse();
    }

    JsonNode get(String path) {
        HttpResponse<String> response = api.get(path, member);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    JsonNode callAiProbe(Map<String, Object> payload) throws Exception {
        String python = System.getenv("BEEZ_131_PYTHON");
        String ai = System.getenv("BEEZ_131_AI_DIR");
        String probe = System.getenv("BEEZ_131_PROBE");
        if (python == null || ai == null || probe == null) throw new IllegalStateException("131 test runtime is not configured");
        ProcessBuilder builder = new ProcessBuilder(python, probe);
        builder.directory(Path.of(ai).toFile());
        builder.environment().put("PYTHONPATH", ai);
        builder.environment().put("PYTHONNOUSERSITE", "1");
        builder.environment().put("PYTHONUTF8", "1");
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().remove("GMS_KEY");
        builder.environment().remove("AI_INTERNAL_TOKEN");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child = builder.start();
        var writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cross-stack-fixture-stdin");
            thread.setDaemon(true);
            return thread;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            byte[] request = JSON.writeValueAsBytes(payload);
            var write = writer.submit(() -> {
                try (var input = child.getOutputStream()) { input.write(request); }
                return null;
            });
            write.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (!child.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS))
                throw new IllegalStateException("AI fixture probe timed out");
            if (child.exitValue() != 0) throw new IllegalStateException("AI fixture probe failed");
            return JSON.readTree(new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
            writer.shutdownNow();
        }
    }
}
