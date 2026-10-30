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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    // Shared across the test JVM and returned once the bucket is ready. The bucket is shared, so object counts
    // are compared against this test's own starting count only.
    static final SeaweedTestContainer seaweed = SeaweedTestContainer.shared();
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
            var locator = locator(1, lastLine);
            var span = Map.<String, Object>of("block_id", "fixture-block", "start_offset", 0,
                    "end_offset", text.codePointCount(0, text.length()), "locator", locator);
            return chunkResponse(input, List.of(chunk(input, "fixture-0", 0, text, text, List.of(span), locator)), List.of());
        });
        when(ai.documentEmbeddings(anyMap())).thenAnswer(invocation -> embedding(invocation.getArgument(0), 1024));
        login(leader);
    }

    static Map<String, Object> locator(int start, int end) {
        return Map.of("type", "text_lines", "start_line", start, "end_line", end);
    }

    static Map<String, Object> chunk(Map<String, Object> input, String key, int number, String text,
                                     String embeddingText, List<Map<String, Object>> spans, Map<String, Object> locator) {
        return Map.of("chunk_key", key, "chunk_no", number, "document_version_id", input.get("document_version_id"),
                "text", text, "embedding_text", embeddingText, "source_spans", spans, "context_refs", List.of(),
                "token_count", text.codePointCount(0, text.length()), "locator", locator);
    }

    static Map<String, Object> chunkResponse(Map<String, Object> input, List<Map<String, Object>> chunks,
                                             List<Map<String, Object>> excluded) {
        var profile = Map.of("profile_id", input.get("chunking_profile_id"), "tokenizer_id", "fixture-v1",
                "max_tokens", 600, "include_headings", false, "include_table_headers", false, "max_chunks", 1024);
        var manifest = Map.of("project_id", input.get("project_id"), "document_version_id", input.get("document_version_id"),
                "content_hash", input.get("content_hash").toString().replaceFirst("^sha256:", ""),
                "text_hash", input.get("text_hash").toString().replaceFirst("^sha256:", ""),
                "normalization_version", input.get("normalization_profile"),
                "parser_profile", "text/plain".equals(input.get("media_type")) ? "plain-v1" : "markdown-v1",
                "parser_version", "fixture-v1", "chunker_version", "fixture-v1", "chunking_profile", profile,
                "expected_chunk_keys", chunks.stream().map(c -> c.get("chunk_key")).toList());
        var result = new HashMap<String, Object>();
        for (String field : List.of("project_id", "document_version_id", "request_key", "text_hash", "normalization_profile", "chunking_profile_id")) result.put(field, input.get(field));
        result.put("contract_version", "document-chunks-v1"); result.put("parser_version", "fixture-v1");
        result.put("manifest", manifest); result.put("diagnostics", List.of());
        result.put("excluded_ranges", excluded); result.put("chunks", chunks);
        return Map.of("result", result, "origin", "PARSER", "usage", List.of());
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
        return upload(key, bytes, kind, "input.md");
    }
    HttpResponse<String> upload(String key, byte[] bytes, String kind, String filename) throws Exception {
        return multipart("/api/projects/" + project + "/documents", key, bytes,
                Map.of("kind", kind, "title", "요구사항", "domain", "account"), false, TRUSTED_ORIGIN, filename);
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields) throws Exception {
        return multipart(path, key, bytes, fields, false);
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields, boolean multiple) throws Exception {
        return multipart(path, key, bytes, fields, multiple, TRUSTED_ORIGIN);
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields, boolean multiple, String origin) throws Exception {
        return multipart(path, key, bytes, fields, multiple, origin, "input.md");
    }
    HttpResponse<String> multipart(String path, String key, byte[] bytes, Map<String, String> fields, boolean multiple, String origin, String filename) throws Exception {
        String boundary = "beez-fixture-boundary";
        var out = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
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

    @Test void indexesCodePointSpansAndExcludedLinesWithoutTreatingDisplayLinesAsOwnership() throws Exception {
        String firstText = "😀" + "a".repeat(599);
        String secondText = "a".repeat(401) + "\n끝\n";
        String source = firstText + "a".repeat(401) + "\n---\n끝\n";
        doAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            var first = Map.<String, Object>of("block_id", "first", "start_offset", 0, "end_offset", 600, "locator", locator(1, 1));
            var second = Map.<String, Object>of("block_id", "first", "start_offset", 600, "end_offset", 1002, "locator", locator(1, 1));
            var third = Map.<String, Object>of("block_id", "last", "start_offset", 1006, "end_offset", 1008, "locator", locator(3, 3));
            return chunkResponse(input, List.of(
                    chunk(input, "first", 0, firstText, firstText, List.of(first), locator(1, 1)),
                    chunk(input, "second", 1, secondText, secondText, List.of(second, third), locator(1, 3))),
                    List.of(Map.of("locator", locator(2, 2), "reason", "thematic_break")));
        }).when(ai).documentChunks(anyMap());
        var response = upload(UUID.randomUUID().toString(), source.getBytes(StandardCharsets.UTF_8), "REQUIREMENTS");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        var doc = JSON.readTree(response.body()).get("document");
        long id = doc.get("id").asLong(), version = doc.get("latest_draft").get("id").asLong();
        assertThat(worker.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForList("SELECT text FROM document_chunk WHERE document_version_id=? ORDER BY chunk_no", String.class, version))
                .containsExactly(firstText, secondText);
        var saved = jdbc.queryForObject("SELECT source_spans::text FROM document_chunk WHERE document_version_id=? AND chunk_no=1", String.class, version);
        assertThat(JSON.readTree(saved).size()).isEqualTo(2);
        assertThat(JSON.readTree(saved).get(1).get("start_offset").asInt()).isEqualTo(1006);
        verify(ai).documentEmbeddings(argThat(input -> "DOCUMENT".equals(input.get("purpose"))
                && List.of(Map.of("key", "first", "text", firstText), Map.of("key", "second", "text", secondText))
                .equals(input.get("texts"))));
        publish(id, version);
        var search = send("GET", "/api/projects/" + project + "/search?q=test&mode=SEMANTIC", null, false);
        assertThat(search.statusCode()).withFailMessage(search.body()).isEqualTo(200);
        var hits = JSON.readTree(search.body()).get("items");
        assertThat(hits.size()).isEqualTo(2);
        var precise = hits.get(1);
        assertThat(precise.get("chunk_key").asText()).isEqualTo("second");
        assertThat(precise.get("source_spans")).isEqualTo(JSON.readTree(saved));
        assertThat(precise.get("context_refs").size()).isZero();
    }

    @Test @SuppressWarnings("unchecked")
    void storesAndSearchesRecordedAiChunksWithExactUnicodeSourceRanges() throws Exception {
        // Generated by the real AI chunker. Only request-scoped identity and hashes are rebound
        // to this test's uploaded document; chunk text, ranges and metadata remain untouched.
        var fixture = JSON.readValue(getClass().getResourceAsStream("/document-chunks-223.json"), Map.class);
        var fixtureInput = (Map<String, Object>) fixture.get("input");
        String source = (String) fixtureInput.get("content");
        var response = (Map<String, Object>) fixture.get("response");
        var result = (Map<String, Object>) response.get("result");
        var manifest = (Map<String, Object>) result.get("manifest");
        var chunks = (List<Map<String, Object>>) result.get("chunks");
        doAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            assertThat(input.get("content")).isEqualTo(source);
            for (String field : List.of("project_id", "document_version_id", "request_key", "text_hash", "normalization_profile", "chunking_profile_id")) {
                result.put(field, input.get(field));
            }
            manifest.put("project_id", input.get("project_id"));
            manifest.put("document_version_id", input.get("document_version_id"));
            manifest.put("content_hash", input.get("content_hash").toString().replaceFirst("^sha256:", ""));
            manifest.put("text_hash", input.get("text_hash").toString().replaceFirst("^sha256:", ""));
            chunks.forEach(chunk -> chunk.put("document_version_id", input.get("document_version_id")));
            return response;
        }).when(ai).documentChunks(anyMap());

        var uploaded = upload(UUID.randomUUID().toString(), source.getBytes(StandardCharsets.UTF_8), "PLAN");
        assertThat(uploaded.statusCode()).withFailMessage(uploaded.body()).isEqualTo(201);
        var document = JSON.readTree(uploaded.body()).get("document");
        long id = document.get("id").asLong(), version = document.get("latest_draft").get("id").asLong();
        assertThat(worker.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        var rows = jdbc.queryForList("SELECT chunk_key,text,source_spans::text AS spans,context_refs::text AS refs FROM document_chunk WHERE document_version_id=? ORDER BY chunk_no", version);
        assertThat(rows).hasSize(chunks.size());
        int[] codePoints = source.codePoints().toArray();
        for (int i = 0; i < chunks.size(); i++) {
            var expected = chunks.get(i);
            var row = rows.get(i);
            assertThat(row.get("chunk_key")).isEqualTo(expected.get("chunk_key"));
            assertThat(row.get("text")).isEqualTo(expected.get("text"));
            var spans = JSON.readTree(row.get("spans").toString());
            assertThat(spans).isEqualTo(JSON.readTree(JSON.writeValueAsString(expected.get("source_spans"))));
            assertThat(JSON.readTree(row.get("refs").toString())).isEqualTo(JSON.readTree(JSON.writeValueAsString(expected.get("context_refs"))));
            var restored = new StringBuilder();
            for (var span : spans) {
                int start = span.get("start_offset").asInt(), end = span.get("end_offset").asInt();
                restored.append(new String(codePoints, start, end - start));
            }
            assertThat(restored.toString()).isEqualTo(row.get("text"));
        }
        publish(id, version);
        var search = send("GET", "/api/projects/" + project + "/search?q=fixture&mode=SEMANTIC&limit=10", null, false);
        assertThat(search.statusCode()).withFailMessage(search.body()).isEqualTo(200);
        var hits = JSON.readTree(search.body()).get("items");
        assertThat(hits.size()).isEqualTo(chunks.size());
        for (var hit : hits) {
            var row = rows.stream().filter(r -> r.get("chunk_key").equals(hit.get("chunk_key").asText())).findFirst().orElseThrow();
            assertThat(hit.get("source_spans")).isEqualTo(JSON.readTree(row.get("spans").toString()));
            assertThat(hit.get("context_refs")).isEqualTo(JSON.readTree(row.get("refs").toString()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong_text", "gap", "overlap", "manifest"})
    void malformedChunkResponseCannotReplacePreviouslyPublishedIndex(String defect) throws Exception {
        var document = uploaded();
        long version = document.get("latest_draft").get("id").asLong();
        worker.runOnce();
        var previousText = jdbc.queryForObject("SELECT text FROM document_chunk WHERE document_version_id=?", String.class, version);
        var previousAttempt = jdbc.queryForObject("SELECT attempt FROM document_index_manifest WHERE document_version_id=?", Integer.class, version);
        jdbc.update("UPDATE document_processing SET attempt=attempt+1,status='PENDING',source_status='PENDING',index_status='PENDING',lease_token=null,lease_until=null WHERE document_version_id=?", version);
        doAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            String text = input.get("content").toString();
            int lastLine = text.split("\n", -1).length - (text.endsWith("\n") ? 1 : 0);
            var locator = locator(1, lastLine);
            int length = text.codePointCount(0, text.length());
            int start = defect.equals("wrong_text") ? 1 : 0;
            int end = defect.equals("gap") ? length - 1 : length;
            var span = Map.<String, Object>of("block_id", "invalid", "start_offset", start, "end_offset", end, "locator", locator);
            var spans = defect.equals("overlap") ? List.of(span, span) : List.of(span);
            String chunkText = defect.equals("gap") ? text.substring(0, text.length() - 1)
                    : defect.equals("overlap") ? text + text : text;
            var output = chunkResponse(input, List.of(chunk(input, "bad", 0, chunkText, chunkText, spans, locator)), List.of());
            if (defect.equals("manifest")) {
                @SuppressWarnings("unchecked") var result = (Map<String, Object>) output.get("result");
                @SuppressWarnings("unchecked") var original = (Map<String, Object>) result.get("manifest");
                var badManifest = new HashMap<>(original); badManifest.put("expected_chunk_keys", List.of("other"));
                result.put("manifest", badManifest);
            }
            return output;
        }).when(ai).documentChunks(anyMap());
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT index_status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT text FROM document_chunk WHERE document_version_id=?", String.class, version)).isEqualTo(previousText);
        assertThat(jdbc.queryForObject("SELECT attempt FROM document_index_manifest WHERE document_version_id=?", Integer.class, version)).isEqualTo(previousAttempt);
    }

    @Test void embeddingRequestUsesEmbeddingTextAndKeepsContextReferences() throws Exception {
        String heading = "# 제목\n", body = "본문\n";
        String headingHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(heading.getBytes(StandardCharsets.UTF_8)));
        doAnswer(invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            int boundary = heading.codePointCount(0, heading.length());
            var firstSpan = Map.<String, Object>of("block_id", "heading", "start_offset", 0,
                    "end_offset", boundary, "locator", locator(1, 1));
            var bodySpan = Map.<String, Object>of("block_id", "body", "start_offset", boundary,
                    "end_offset", boundary + body.codePointCount(0, body.length()), "locator", locator(2, 2));
            var ref = Map.<String, Object>of("block_id", "heading", "role", "heading", "locator", locator(1, 1),
                    "text_hash", headingHash, "applied", false);
            var bodyChunk = new HashMap<>(chunk(input, "body", 1, body, body, List.of(bodySpan), locator(2, 2)));
            bodyChunk.put("context_refs", List.of(ref));
            return chunkResponse(input, List.of(chunk(input, "heading", 0, heading, heading, List.of(firstSpan), locator(1, 1)), bodyChunk), List.of());
        }).when(ai).documentChunks(anyMap());
        var response = upload(UUID.randomUUID().toString(), (heading + body).getBytes(StandardCharsets.UTF_8), "REQUIREMENTS");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        long version = JSON.readTree(response.body()).get("document").get("latest_draft").get("id").asLong();
        worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        verify(ai).documentEmbeddings(argThat(input -> "DOCUMENT".equals(input.get("purpose"))
                && List.of(Map.of("key", "heading", "text", heading), Map.of("key", "body", "text", body)).equals(input.get("texts"))));
        var stored = jdbc.queryForObject("SELECT context_refs::text FROM document_chunk WHERE document_version_id=? AND chunk_no=1", String.class, version);
        assertThat(JSON.readTree(stored).get(0).get("text_hash").asText()).isEqualTo(headingHash);
        assertThat(JSON.readTree(stored).get(0).get("applied").asBoolean()).isFalse();
    }

    @Test void indexesPlainTextUploadThroughThePlainTextChunkingContract() throws Exception {
        // The upload policy keeps .txt; the chunking API reads it as plain text (plain-v1), never as Markdown.
        var response = upload(UUID.randomUUID().toString(), "계획 메모 <user> 태그\n두 번째 줄\n".getBytes(StandardCharsets.UTF_8), "PLAN", "notes.txt");
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(201);
        long version = JSON.readTree(response.body()).get("document").get("latest_draft").get("id").asLong();
        assertThat(worker.runOnce()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        verify(ai).documentChunks(argThat(input -> "text/plain".equals(input.get("media_type")) && "PLAN".equals(input.get("kind"))));
        var markdown = uploaded();
        assertThat(worker.runOnce()).isTrue();
        verify(ai).documentChunks(argThat(input -> "text/markdown".equals(input.get("media_type"))
                && markdown.get("latest_draft").get("id").asText().equals(input.get("document_version_id"))));
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
        jdbc.update("UPDATE document_index_manifest SET embedding_profile_id='test-profile' WHERE document_version_id=?", current);
        jdbc.update("UPDATE document_processing SET attempt=attempt+1,status='PENDING',index_status='PENDING' WHERE document_version_id=?", current);
        assertThat(send("GET", path, null, false).statusCode()).isEqualTo(409);
        // Even if status is accidentally restored, an older manifest must not be served.
        jdbc.update("UPDATE document_processing SET status='COMPLETED',index_status='READY' WHERE document_version_id=?", current);
        assertThat(send("GET", path, null, false).statusCode()).isEqualTo(409);
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
