package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.config.BeezProperties;
import com.beez.backend.document.service.DocumentIndexWorker;
import com.beez.backend.document.support.SeaweedTestContainer;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.beez.backend.document.storage.DocumentStorage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"beez.cors.allowed-origins=https://app.beez.test", "beez.document.worker-enabled=false", "beez.document.embedding-profile=test-profile", "beez.document.embedding-model=test-model"})
@ActiveProfiles("test")
@Testcontainers
@Import(DocumentApiIntegrationTest.Support.class)
class DocumentApiIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(
            "pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d").asCompatibleSubstituteFor("postgres"));
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Container static final SeaweedTestContainer seaweed = new SeaweedTestContainer();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("beez.document.object-storage.endpoint", seaweed::endpoint);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment env;
    @Autowired DocumentIndexWorker worker;
    @Autowired AiClient ai;
    @MockitoSpyBean DocumentStorage documentStorage;
    @Autowired software.amazon.awssdk.services.s3.S3Client storageClient;
    long leader, member, project;
    static final String TRUSTED_ORIGIN = "https://app.beez.test";
    static final String PASSWORD = "document-test-123";
    String base;
    HttpClient http;
    final byte[] content = "\uFEFF# 요구사항\r\nREQ-01: 로그인한 멤버만 조회한다.\r\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void setUp() throws Exception {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(storageClient.headBucket(software.amazon.awssdk.services.s3.model.HeadBucketRequest.builder()
                        .bucket(SeaweedTestContainer.BUCKET).build())).isNotNull());
        jdbc.update("UPDATE document_processing SET status='FAILED',source_status='FAILED',index_status='FAILED',error_code='test_reset',lease_token=null,lease_until=null WHERE status IN ('PENDING','RUNNING')");
        base = "http://localhost:" + env.getProperty("local.server.port");
        http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        leader = register("leader");
        member = register("member");
        project = jdbc.queryForObject("INSERT INTO project(name,owner_account_id) VALUES ('documents',?) RETURNING id", Long.class, leader);
        jdbc.update("INSERT INTO project_member(project_id,account_id,role) VALUES (?,?,'LEADER'),(?,?,'MEMBER')", project, leader, project, member);
        reset(ai);
        reset(documentStorage);
        when(ai.documentChunks(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            String text = input.get("content").toString();
            int lastLine = text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0);
            var result = new HashMap<String, Object>();
            for (String field : List.of("document_version_id", "request_key", "text_hash", "chunking_profile_id")) result.put(field, input.get(field));
            result.put("parser_version", "fixture-v1"); result.put("diagnostics", List.of());
            result.put("chunks", List.of(Map.of("chunk_key", "fixture-0", "chunk_no", 0, "text", text,
                    "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", lastLine))));
            return Map.of("result", result, "origin", "PARSER", "usage", List.of());
        });
        when(ai.documentEmbeddings(anyMap())).thenAnswer(invocation -> embedding(invocation.getArgument(0), 1024));
        login(leader);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> embedding(Map<String, Object> input, int dimension) {
        var vector = new ArrayList<Double>(Collections.nCopies(dimension, 0.0)); vector.set(0, 1.0);
        var texts = (List<Map<String, Object>>) input.get("texts");
        return Map.of("origin", "MODEL", "usage", List.of(Map.of("request_key", input.get("request_key") + "-provider-1",
                "feature", "EMBEDDING", "model", "test-model", "input_tokens", 3, "output_tokens", 0,
                "latency_ms", 1, "tokens_known", true, "failed", false, "estimated_cost_amount", "0.000001", "currency", "USD")), "model", "test-model", "result",
                Map.of("model", "test-model", "dimension", dimension, "embedding_profile_id", "test-profile", "purpose", input.get("purpose"),
                        "vectors", texts.stream().map(t -> Map.of("key", t.get("key"), "embedding", vector)).toList()));
    }
    long register(String name) throws Exception {
        var body = JSON.writeValueAsString(Map.of("email", UUID.randomUUID() + "@documents.test", "password", PASSWORD, "name", name));
        var response = send("POST", "/api/auth/register", body, false);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("id").asLong();
    }
    void login(long account) throws Exception {
        String email = jdbc.queryForObject("SELECT email FROM account WHERE id=?", String.class, account);
        var response = send("POST", "/api/auth/login", JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)), false);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    }
    HttpResponse<String> send(String method, String path, String body, boolean trustedOrigin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path));
        if (!method.equals("GET")) builder.header("Idempotency-Key", UUID.randomUUID().toString());
        if (trustedOrigin) builder.header("Origin", TRUSTED_ORIGIN);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> upload(String key, byte[] bytes, String kind) throws Exception {
        return multipart("/api/projects/" + project + "/documents", key, bytes,
                Map.of("kind", kind, "title", "요구사항", "domain", "account"));
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields) throws Exception {
        return multipart(path, key, bytes, fields, false);
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields, boolean multiple) throws Exception {
        return multipart(path, key, bytes, fields, multiple, TRUSTED_ORIGIN);
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields, boolean multiple, String origin) throws Exception {
        String boundary = "beez-fixture-boundary";
        var out = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.md\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        if (multiple) out.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"second.md\"\r\n\r\nsecond").getBytes(StandardCharsets.UTF_8));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", key);
        if (origin != null) builder.header("Origin", origin);
        return http.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode uploaded() throws Exception {
        var response = upload(UUID.randomUUID().toString(), content, "REQUIREMENTS");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        return JSON.readTree(response.body()).get("document");
    }
    long publish(long document, long version) {
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE id=?", leader, version);
        int number = jdbc.queryForObject("SELECT coalesce(max(version_no),0)+1 FROM baseline WHERE project_id=?", Integer.class, project);
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES (?,?,?) RETURNING id", Long.class, project, number, leader);
        jdbc.update("INSERT INTO baseline_document(baseline_id,project_id,document_id,document_version_id) VALUES (?,?,?,?)", baseline, project, document, version);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project);
        return baseline;
    }

    @Test void httpUploadIsAtomicIdempotentAndReadableWithExactOriginalDownload() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = upload(key, content, "REQUIREMENTS"); assertThat(first.statusCode()).withFailMessage(first.body()).isEqualTo(201);
        var repeated = upload(key, content, "REQUIREMENTS"); assertThat(JSON.readTree(repeated.body())).isEqualTo(JSON.readTree(first.body()));
        assertThat(upload(key, "changed".getBytes(StandardCharsets.UTF_8), "REQUIREMENTS").statusCode()).isEqualTo(409);
        assertThat(upload(UUID.randomUUID().toString(), content, "REQUIREMENTS").statusCode()).isEqualTo(409);
        var document = JSON.readTree(first.body()).get("document"); String id = document.get("id").asText();
        long version = document.get("latest_draft").get("id").asLong();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_processing WHERE document_version_id=? AND status='PENDING'", Integer.class, version)).isEqualTo(1);
        String path = "/api/projects/" + project + "/documents/" + id;
        assertThat(send("GET", path, null, false).statusCode()).isEqualTo(409);
        var original = send("GET", path + "?version_no=1", null, false);
        assertThat(JSON.readTree(original.body()).get("version").get("content").asText()).isEqualTo("# 요구사항\nREQ-01: 로그인한 멤버만 조회한다.\n");
        var bytes = http.send(HttpRequest.newBuilder(URI.create(base + path + "/versions/1/file")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(bytes.body()).containsExactly(content);
        assertThat(bytes.headers().firstValue("Content-Disposition").orElseThrow()).contains("attachment", "document-" + id + "-v1.md");
        assertThat(send("PATCH", path, "{\"domain\":\"billing\"}", true).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/projects/" + project + "/documents?domain=billing&limit=1", null, false).body()).contains("billing");
        assertThat(send("GET", path + "/versions", null, false).statusCode()).isEqualTo(200);
        assertThat(send("GET", path + "/diff?from_version=1&to_version=1", null, false).body()).contains("\"diff\":\"\"");
    }

    @Test void rejectsHeaderSpoofingMissingOriginAndRemovedMemberButAllowsMemberUpload() throws Exception {
        var document = uploaded(); String path = "/api/projects/" + project + "/documents/" + document.get("id").asText();
        var anonymous = HttpClient.newHttpClient();
        var spoofed = anonymous.send(HttpRequest.newBuilder(URI.create(base + path + "?version_no=1")).header("X-Beez-Account-Id", Long.toString(leader)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(spoofed.statusCode()).isEqualTo(401);
        assertThat(send("PATCH", path, "{\"domain\":\"other\"}", false).statusCode()).isEqualTo(403);
        login(member);
        assertThat(send("GET", path + "?version_no=1", null, false).statusCode()).isEqualTo(200);
        assertThat(upload(UUID.randomUUID().toString(), content, "PLAN").statusCode()).isEqualTo(201);
        jdbc.update("UPDATE project_member SET active=false WHERE project_id=? AND account_id=?", project, member);
        assertThat(send("GET", path + "?version_no=1", null, false).statusCode()).isEqualTo(404);
        login(leader);
        long foreign = jdbc.queryForObject("INSERT INTO project(name,owner_account_id) VALUES ('foreign',?) RETURNING id", Long.class, leader);
        assertThat(send("GET", "/api/projects/" + foreign + "/documents/" + document.get("id").asText(), null, false).statusCode()).isEqualTo(404);
    }

    @Test void indexesDurablySearchesOnlyCurrentBaselineAndPreservesApprovedSource() throws Exception {
        var document = uploaded(); long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        String path = "/api/projects/" + project + "/documents/" + id;
        assertThat(worker.runOnce()).isTrue();
        var state = JSON.readTree(send("GET", path + "/analysis?document_version_id=" + version, null, false).body());
        assertThat(state.get("status").asText()).isEqualTo("COMPLETED"); assertThat(state.get("index_status").asText()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_index_manifest WHERE document_version_id=?", Integer.class, version)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estimated_cost_amount FROM llm_usage WHERE project_id=?", java.math.BigDecimal.class, project)).isEqualByComparingTo("0.000001");
        assertThat(JSON.readTree(send("GET", "/api/projects/" + project + "/search?q=REQ-01&mode=KEYWORD", null, false).body()).get("items").size()).isZero();
        publish(id, version);
        assertThat(send("GET", "/api/projects/" + project + "/baselines/current", null, false).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/projects/" + project + "/baselines/1", null, false).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/projects/" + project + "/baselines?limit=1", null, false).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(send("GET", "/api/projects/" + project + "/search?q=REQ-01&mode=KEYWORD", null, false).body()).get("items").size()).isEqualTo(1);
        var semantic = send("GET", "/api/projects/" + project + "/search?q=REQ-01&mode=SEMANTIC", null, false);
        assertThat(semantic.statusCode()).withFailMessage(semantic.body()).isEqualTo(200);
        assertThat(JSON.readTree(semantic.body()).get("items").size()).isEqualTo(1);
        var draft = multipart(path + "/draft-versions", UUID.randomUUID().toString(), "# 변경\n미승인-숨김\n".getBytes(StandardCharsets.UTF_8), Map.of("base_document_version_id", Long.toString(version)));
        assertThat(draft.statusCode()).withFailMessage(draft.body()).isEqualTo(201);
        assertThat(JSON.readTree(send("GET", path, null, false).body()).get("version").get("id").asLong()).isEqualTo(version);
        assertThat(JSON.readTree(send("GET", "/api/projects/" + project + "/search?q=미승인&mode=KEYWORD", null, false).body()).get("items").size()).isZero();
        assertThat(multipart(path + "/draft-versions", UUID.randomUUID().toString(), content, Map.of("base_document_version_id", "99999")).statusCode()).isEqualTo(409);
    }

    @Test void failedEmbeddingHasNoPartialIndexAndRetryPreservesInput() throws Exception {
        var document = uploaded(); long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        String path = "/api/projects/" + project + "/documents/" + id;
        doAnswer(invocation -> embedding(invocation.getArgument(0), 3)).when(ai).documentEmbeddings(anyMap());
        worker.runOnce();
        var state = JSON.readTree(send("GET", path + "/analysis?document_version_id=" + version, null, false).body());
        assertThat(state.get("status").asText()).isEqualTo("FAILED"); assertThat(state.get("source_status").asText()).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE document_version_id=?", Integer.class, version)).isZero();
        var retry = send("POST", path + "/analysis/retry", "{\"document_version_id\":\"" + version + "\"}", true);
        assertThat(retry.statusCode()).withFailMessage(retry.body()).isEqualTo(202);
        assertThat(JSON.readTree(retry.body()).get("attempt").asInt()).isEqualTo(2);
        doAnswer(invocation -> embedding(invocation.getArgument(0), 1024)).when(ai).documentEmbeddings(anyMap());
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?", String.class, version)).contains("REQ-01");
    }

    @Test void semanticSearchKeepsExactScopedResultsWithManyCloserForeignAndHistoricalChunks() throws Exception {
        var document = uploaded();
        long id = document.get("id").asLong(), old = document.get("latest_draft").get("id").asLong();
        worker.runOnce(); publish(id, old);
        var draft = multipart("/api/projects/" + project + "/documents/" + id + "/draft-versions", UUID.randomUUID().toString(), content,
                Map.of("base_document_version_id", Long.toString(old)));
        long current = JSON.readTree(draft.body()).get("id").asLong();
        worker.runOnce(); publish(id, current);
        jdbc.update("UPDATE document_chunk SET text='allowed-0',embedding=(ARRAY[1.0,0.1]::double precision[] || array_fill(0.0::double precision,ARRAY[1022]))::vector WHERE document_version_id=?", current);
        jdbc.update("""
                INSERT INTO document_chunk(document_version_id,chunk_no,text,locator,embedding)
                SELECT ?,g,'allowed-'||g,'{"type":"text_lines","start_line":1,"end_line":1}'::jsonb,
                  (ARRAY[1.0,(g+1)/10.0]::double precision[] || array_fill(0.0::double precision,ARRAY[1022]))::vector
                FROM generate_series(1,11) g
                """, current);
        jdbc.update("UPDATE document_index_manifest SET chunk_count=12 WHERE document_version_id=?", current);
        long foreignProject = jdbc.queryForObject("INSERT INTO project(name,owner_account_id) VALUES ('foreign-search',?) RETURNING id", Long.class, leader);
        long foreignDocument = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES (?,'REQ_SPEC','foreign',?) RETURNING id", Long.class, foreignProject, leader);
        long foreignVersion = jdbc.queryForObject("INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash) VALUES (?,1,'DRAFT','fixture.md','fixture') RETURNING id", Long.class, foreignDocument);
        // All noise is closer to the query than every permitted chunk.
        for (long excluded : List.of(old, foreignVersion)) {
            jdbc.update("""
                    INSERT INTO document_chunk(document_version_id,chunk_no,text,locator,embedding)
                    SELECT ?,g,'excluded-'||g,'{"type":"text_lines","start_line":1,"end_line":1}'::jsonb,
                      (ARRAY[1.0]::double precision[] || array_fill(0.0::double precision,ARRAY[1023]))::vector
                    FROM generate_series(1,512) g
                    """, excluded);
        }
        jdbc.update("INSERT INTO document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count) VALUES (?,1,'fixture','other-profile','fixture','fixture',512)", foreignVersion);
        jdbc.execute("ANALYZE document_chunk");
        String path = "/api/projects/" + project + "/search?q=scope&mode=SEMANTIC&domain=account&kind=REQUIREMENTS&limit=5";
        var ids = new HashSet<String>();
        String cursor = null;
        for (int page = 0; page < 3; page++) {
            var response = send("GET", path + (cursor == null ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)), null, false);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
            var result = JSON.readTree(response.body());
            assertThat(result.get("items").size()).isEqualTo(page < 2 ? 5 : 2);
            int index = page * 5;
            for (var hit : result.get("items")) {
                assertThat(hit.get("snippet").asText()).isEqualTo("allowed-" + index++);
                assertThat(hit.get("source").get("document_version_id").asLong()).isEqualTo(current);
                assertThat(ids.add(hit.get("id").asText())).isTrue();
            }
            assertThat(result.get("has_more").asBoolean()).isEqualTo(page < 2);
            cursor = page < 2 ? result.get("next_cursor").asText() : null;
        }
        assertThat(ids).hasSize(12);
        assertThat(JSON.readTree(send("GET", path.replace("domain=account", "domain=other"), null, false).body()).get("items").size()).isZero();
        assertThat(JSON.readTree(send("GET", path.replace("kind=REQUIREMENTS", "kind=PLAN"), null, false).body()).get("items").size()).isZero();
        jdbc.update("UPDATE document_index_manifest SET embedding_profile_id='other-profile' WHERE document_version_id=?", current);
        var mismatch = send("GET", path, null, false);
        assertThat(mismatch.statusCode()).isEqualTo(409);
        assertThat(mismatch.body()).contains("index_not_ready");
    }

    @Test void rejectsMalformedTextAndOversizedMultipartWithoutPersistingAnything() throws Exception {
        assertThat(multipart("/api/projects/" + project + "/documents", UUID.randomUUID().toString(), content,
                Map.of("kind", "PLAN", "title", "중복 파일"), true).statusCode()).isEqualTo(400);
        assertThat(upload(UUID.randomUUID().toString(), new byte[] {(byte) 0xc3, 0x28}, "PLAN").statusCode()).isEqualTo(400);
        assertThat(upload(UUID.randomUUID().toString(), new byte[1_048_577], "PLAN").statusCode()).isEqualTo(413);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE project_id=?", Integer.class, project)).isZero();
    }

    @Test void expiredLeaseRecoversAndLateAttemptCannotPublish() throws Exception {
        var document = uploaded(); long version = document.get("latest_draft").get("id").asLong();
        jdbc.update("UPDATE document_processing SET status='RUNNING',source_status='RUNNING',lease_token=?,lease_until=now()-interval '1 minute' WHERE document_version_id=?", UUID.randomUUID(), version);
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            if (replaced.compareAndSet(false, true)) {
                jdbc.update("UPDATE document_processing SET attempt=attempt+1,status='PENDING',source_status='PENDING',index_status='PENDING',lease_token=null,lease_until=null WHERE document_version_id=?", version);
            }
            return embedding(invocation.getArgument(0), 1024);
        }).when(ai).documentEmbeddings(anyMap());
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT attempt FROM document_processing WHERE document_version_id=?", Integer.class, version)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk WHERE document_version_id=?", Integer.class, version)).isZero();
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT attempt FROM document_index_manifest WHERE document_version_id=?", Integer.class, version)).isEqualTo(3);
    }

    @Test void expiredLeaseStopsAfterTwoRecoveriesAndExplicitRetryResetsBudget() throws Exception {
        var document = uploaded();
        long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        // Simulate repeated process loss after a claim: no publish/failure callback occurs.
        for (int recovery = 0; recovery < 2; recovery++) {
            jdbc.update("UPDATE document_processing SET status='RUNNING',source_status='RUNNING',lease_token=?,lease_until=now()-interval '1 minute' WHERE document_version_id=?", UUID.randomUUID(), version);
            assertThat(worker.runOnce()).isTrue();
            assertThat(jdbc.queryForObject("SELECT automatic_recoveries FROM document_processing WHERE document_version_id=?", Integer.class, version)).isEqualTo(recovery + 1);
        }
        jdbc.update("UPDATE document_processing SET status='RUNNING',source_status='READY',index_status='RUNNING',lease_token=?,lease_until=now()-interval '1 minute' WHERE document_version_id=?", UUID.randomUUID(), version);
        clearInvocations(ai);
        assertThat(worker.runOnce()).isFalse();
        assertThat(worker.runOnce()).isFalse();
        verifyNoInteractions(ai);
        var failed = jdbc.queryForMap("SELECT * FROM document_processing WHERE document_version_id=?", version);
        assertThat(failed).containsEntry("status", "FAILED").containsEntry("source_status", "READY")
                .containsEntry("index_status", "FAILED").containsEntry("error_code", "lease_recovery_exhausted")
                .containsEntry("attempt", 3).containsEntry("lease_token", null).containsEntry("lease_until", null);
        assertThat(jdbc.queryForObject("SELECT parse_status FROM document_version WHERE id=?", String.class, version)).isEqualTo("OK");
        var retry = send("POST", "/api/projects/" + project + "/documents/" + id + "/analysis/retry",
                "{\"document_version_id\":\"" + version + "\"}", true);
        assertThat(retry.statusCode()).withFailMessage(retry.body()).isEqualTo(202);
        assertThat(jdbc.queryForObject("SELECT automatic_recoveries FROM document_processing WHERE document_version_id=?", Integer.class, version)).isZero();
        assertThat(jdbc.queryForObject("SELECT attempt FROM document_processing WHERE document_version_id=?", Integer.class, version)).isEqualTo(4);
        assertThat(worker.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
    }

    @Test void uploadPutHasNoTransactionAndDoesNotBlockProjectForeignKeyInsert() throws Exception {
        assertPutAllowsForeignKeyInsert(() -> upload(UUID.randomUUID().toString(), content, "REQUIREMENTS"));
    }

    @Test void draftPutHasNoTransactionAndRechecksBaselineAfterPut() throws Exception {
        var document = uploaded();
        long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        publish(id, version);
        int originals = originalCount();
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Object result = invocation.callRealMethod();
            jdbc.update("UPDATE project SET current_baseline_id=null WHERE id=?", project);
            return result;
        }).when(documentStorage).store(any(), anyString());
        var response = multipart("/api/projects/" + project + "/documents/" + id + "/draft-versions", UUID.randomUUID().toString(), content,
                Map.of("base_document_version_id", Long.toString(version)));
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("version_conflict");
        assertThat(originalCount()).isEqualTo(originals);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test void draftPutDoesNotBlockProjectForeignKeyInsert() throws Exception {
        var document = uploaded();
        long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        publish(id, version);
        assertPutAllowsForeignKeyInsert(() -> multipart("/api/projects/" + project + "/documents/" + id + "/draft-versions",
                UUID.randomUUID().toString(), content, Map.of("base_document_version_id", Long.toString(version))));
    }

    void assertPutAllowsForeignKeyInsert(java.util.concurrent.Callable<HttpResponse<String>> request) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(documentStorage).store(any(), anyString());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var upload = executor.submit(request);
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var insert = executor.submit(() -> jdbc.update("INSERT INTO document_http_command(project_id,account_id,operation,request_key,fingerprint,response) VALUES (?,?,'fk-probe',?,'probe','{}'::jsonb)", project, leader, UUID.randomUUID().toString()));
                assertThat(insert.get(3, TimeUnit.SECONDS)).isEqualTo(1);
            } finally { release.countDown(); }
            var response = upload.get(15, TimeUnit.SECONDS);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        }
    }

    @Test void concurrentIdempotentUploadsKeepOnlyOneOriginalAndJob() throws Exception {
        int originals = originalCount();
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(documentStorage).store(any(), anyString());
        String key = UUID.randomUUID().toString();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> upload(key, content, "REQUIREMENTS"));
            var second = executor.submit(() -> upload(key, content, "REQUIREMENTS"));
            try { assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { release.countDown(); }
            var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.statusCode()).withFailMessage(a.body()).isEqualTo(201);
            assertThat(b.statusCode()).withFailMessage(b.body()).isEqualTo(201);
            assertThat(JSON.readTree(a.body())).isEqualTo(JSON.readTree(b.body()));
        }
        assertThat(originalCount()).isEqualTo(originals + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_processing r JOIN document_version v ON v.id=r.document_version_id JOIN document d ON d.id=v.document_id WHERE d.project_id=?", Integer.class, project)).isEqualTo(1);
    }

    @Test void databaseFailureAfterRegistrationRollsBackFileDocumentAndJob() throws Exception {
        int filesBefore = originalCount();
        jdbc.execute("ALTER TABLE document_http_command ADD CONSTRAINT fixture_reject_upload CHECK (operation <> 'upload') NOT VALID");
        try {
            assertThat(upload(UUID.randomUUID().toString(), content, "REQUIREMENTS").statusCode()).isEqualTo(400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE project_id=?", Integer.class, project)).isZero();
            assertThat(originalCount()).isEqualTo(filesBefore);
        } finally {
            jdbc.execute("ALTER TABLE document_http_command DROP CONSTRAINT fixture_reject_upload");
        }
    }

    @Test void acceptsTrustedRefererWithoutTokenAndRejectsMissingOrBadSource() throws Exception {
        var document = uploaded();
        String path = "/api/projects/" + project + "/documents/" + document.get("id").asText();
        var refererOnly = http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Referer", TRUSTED_ORIGIN + "/documents")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"domain\":\"referer-accepted\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refererOnly.statusCode()).withFailMessage(refererOnly.body()).isEqualTo(200);
        var rejected = send("PATCH", path, "{\"domain\":\"rejected\"}", false);
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(rejected.body()).contains("request_origin_rejected");
        assertThat(send("GET", "/api/projects/" + project + "/documents", null, false).body()).contains("referer-accepted");
        var badReferer = http.send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Referer", "https://evil.test/documents")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"domain\":\"rejected\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(badReferer.statusCode()).isEqualTo(403);
    }

    @Test void multipartWithoutTrustedOriginIsRejectedBeforeStorage() throws Exception {
        int filesBefore = originalCount();
        for (String origin : new String[] { null, "null", "https://evil.test" }) {
            var response = multipart("/api/projects/" + project + "/documents", UUID.randomUUID().toString(), content,
                    Map.of("kind", "PLAN", "title", "blocked"), false, origin);
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(originalCount()).isEqualTo(filesBefore);
    }

    @Test void logoutInvalidatesTheDocumentSessionAndDedicatedCsrfEndpointIsRemoved() throws Exception {
        assertThat(send("GET", "/api/session/csrf", null, false).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/auth/logout", null, false).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/projects/" + project + "/documents", null, false).statusCode()).isEqualTo(401);
    }

    @Test void failedDraftCommandRollsBackNewOriginalAndKeepsApprovedSource() throws Exception {
        var document = uploaded();
        long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        publish(id, version);
        int filesBefore = originalCount();
        jdbc.execute("ALTER TABLE document_http_command ADD CONSTRAINT fixture_reject_draft CHECK (operation NOT LIKE 'draft:%') NOT VALID");
        try {
            var response = multipart("/api/projects/" + project + "/documents/" + id + "/draft-versions",
                    UUID.randomUUID().toString(), "# changed".getBytes(StandardCharsets.UTF_8),
                    Map.of("base_document_version_id", Long.toString(version)));
            assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(400);
            assertThat(originalCount()).isEqualTo(filesBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_version WHERE document_id=?", Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_processing WHERE document_version_id IN (SELECT id FROM document_version WHERE document_id=?)", Integer.class, id)).isEqualTo(1);
            var downloaded = http.send(HttpRequest.newBuilder(URI.create(base + "/api/projects/" + project
                    + "/documents/" + id + "/versions/1/file")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);
            assertThat(downloaded.body()).containsExactly(content);
        } finally {
            jdbc.execute("ALTER TABLE document_http_command DROP CONSTRAINT fixture_reject_draft");
        }
    }

    int originalCount() {
        return storageClient.listObjectsV2(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.builder()
                .bucket(SeaweedTestContainer.BUCKET).build()).contents().size();
    }

    @TestConfiguration
    static class Support {
        @Bean @Primary AiClient testAi(BeezProperties properties) { return mock(AiClient.class); }
    }
}
