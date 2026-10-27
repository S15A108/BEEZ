package com.beez.backend.document.service;

import static com.beez.backend.document.service.DocumentValues.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.DocumentKindMapper;
import com.beez.backend.document.config.DocumentProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientResponseException;

/** Durable queue with attempt+lease fencing. HTTP work occurs outside DB transactions. */
@Component
public class DocumentIndexWorker {
    private final JdbcClient jdbc;
    private final AiClient ai;
    private final DocumentProperties properties;
    private final TransactionTemplate tx;

    public DocumentIndexWorker(JdbcClient jdbc, AiClient ai, DocumentProperties properties, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.ai = ai; this.properties = properties; this.tx = new TransactionTemplate(manager);
    }
    private List<Map<String, Object>> rows(String sql, Map<String, ?> params) { return jdbc.sql(sql).params(params).query().listOfRows(); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("invalid_ai_output");
        return (Map<String, Object>) map;
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw invalid("invalid_ai_output");
        return list;
    }
    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw invalid("invalid_ai_output");
        return s;
    }
    private static int integer(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.intValue()) throw invalid("invalid_ai_output");
        return n.intValue();
    }

    @Scheduled(fixedDelayString = "${beez.document.poll-millis:2000}")
    public void poll() {
        if (!properties.workerEnabled()) return;
        try { runOnce(); }
        catch (RuntimeException error) { LoggerFactory.getLogger(getClass()).error("Document worker failed: {}", error.getClass().getSimpleName()); }
    }

    public boolean runOnce() {
        var job = tx.execute(status -> {
            // Restart/crash recovery creates a new attempt before any expired worker can publish.
            jdbc.sql("UPDATE document_processing SET attempt=attempt+1,status='PENDING',source_status='PENDING',index_status='PENDING',lease_token=null,lease_until=null,error_code=null,updated_at=now() WHERE status='RUNNING' AND lease_until<now()")
                    .update();
            var pending = rows("""
                    SELECT r.*,v.content_text,v.content_hash,v.document_id,d.project_id,d.type,v.storage_key
                    FROM document_processing r JOIN document_version v ON v.id=r.document_version_id
                    JOIN document d ON d.id=v.document_id JOIN project p ON p.id=d.project_id
                    WHERE r.status='PENDING' AND p.archived_at IS NULL ORDER BY r.updated_at,r.document_version_id
                    LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                    """, Map.of());
            if (pending.isEmpty()) return null;
            var selected = new HashMap<>(pending.getFirst());
            UUID lease = UUID.randomUUID(); selected.put("lease_token", lease);
            jdbc.sql("UPDATE document_processing SET status='RUNNING',source_status='RUNNING',lease_token=:lease,lease_until=now()+interval '10 minutes',updated_at=now() WHERE document_version_id=:id")
                    .param("lease", lease).param("id", selected.get("document_version_id")).update();
            return selected;
        });
        if (job == null) return false;
        try { process(job); }
        catch (RuntimeException error) { fail(job, error); }
        return true;
    }

    private Map<String, Object> fence(Map<String, Object> job) {
        return Map.of("id", job.get("document_version_id"), "attempt", job.get("attempt"), "lease", job.get("lease_token"));
    }
    private boolean sourceReady(Map<String, Object> job) {
        return jdbc.sql("UPDATE document_processing SET source_status='READY',index_status='RUNNING',updated_at=now() WHERE document_version_id=:id AND attempt=:attempt AND lease_token=:lease AND status='RUNNING' AND lease_until>now()")
                .params(fence(job)).update() == 1;
    }
    private String key(Map<String, Object> job, String phase) {
        return "document-" + job.get("document_version_id") + "-attempt-" + job.get("attempt") + "-" + phase + "-" + job.get("lease_token");
    }
    private void process(Map<String, Object> job) {
        String content = text(job.get("content_text"));
        if (!hash(content.getBytes(StandardCharsets.UTF_8)).equals(job.get("text_hash"))) throw invalid("text_hash_mismatch");
        var input = new LinkedHashMap<String, Object>();
        input.put("project_id", job.get("project_id").toString()); input.put("document_version_id", job.get("document_version_id").toString());
        input.put("request_key", key(job, "chunks")); input.put("kind", new DocumentKindMapper().convertToEntityAttribute(job.get("type").toString()).name());
        input.put("media_type", job.get("storage_key").toString().endsWith(".md") ? "text/markdown" : "text/plain");
        input.put("content", content); input.put("content_hash", job.get("content_hash")); input.put("text_hash", job.get("text_hash"));
        input.put("normalization_profile", "utf8-lf-v1"); input.put("chunking_profile_id", "md-structure-v1");
        var response = call(job, input, false);
        if (!"PARSER".equals(response.get("origin")) || !array(response.get("usage")).isEmpty()) throw invalid("invalid_chunk_origin");
        var result = object(response.get("result"));
        for (String field : List.of("document_version_id", "request_key", "text_hash", "chunking_profile_id")) {
            if (!Objects.equals(result.get(field), input.get(field))) throw invalid("invalid_chunk_result");
        }
        text(result.get("parser_version"));
        var chunks = validateChunks(content, result.get("chunks"));
        if (!sourceReady(job)) return;
        configured();
        var embeddingInput = Map.<String, Object>of("project_id", job.get("project_id").toString(),
                "request_key", key(job, "embeddings"), "embedding_profile_id", properties.embeddingProfile(),
                "purpose", "DOCUMENT", "model", properties.embeddingModel(), "dimension", properties.embeddingDimension(),
                "texts", chunks.stream().map(c -> Map.of("key", c.get("chunk_key"), "text", c.get("text"))).toList());
        var embeddings = vectors(call(job, embeddingInput, true), "DOCUMENT");
        if (!embeddings.keySet().equals(chunks.stream().map(c -> text(c.get("chunk_key"))).collect(java.util.stream.Collectors.toSet()))) throw invalid("invalid_embedding_keys");
        tx.executeWithoutResult(status -> {
            var current = rows("SELECT r.*,v.content_hash FROM document_processing r JOIN document_version v ON v.id=r.document_version_id WHERE r.document_version_id=:id AND r.attempt=:attempt AND r.lease_token=:lease AND r.status='RUNNING' AND r.lease_until>now() FOR UPDATE OF r", fence(job));
            if (current.isEmpty() || !current.getFirst().get("content_hash").equals(job.get("content_hash"))
                    || !current.getFirst().get("text_hash").equals(job.get("text_hash"))) return;
            jdbc.sql("DELETE FROM document_chunk WHERE document_version_id=:id").param("id", job.get("document_version_id")).update();
            for (var chunk : chunks) {
                jdbc.sql("INSERT INTO document_chunk(document_version_id,chunk_no,text,locator,embedding) VALUES(:id,:no,:text,cast(:locator AS jsonb),cast(:vector AS vector))")
                        .params(Map.of("id", job.get("document_version_id"), "no", chunk.get("chunk_no"), "text", chunk.get("text"),
                                "locator", JSON.writeValueAsString(chunk.get("locator")), "vector", embeddings.get(chunk.get("chunk_key")))).update();
            }
            jdbc.sql("""
                    INSERT INTO document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                    VALUES(:id,:attempt,:hash,:profile,:chunking,:parser,:count)
                    ON CONFLICT(document_version_id) DO UPDATE SET attempt=excluded.attempt,text_hash=excluded.text_hash,
                    embedding_profile_id=excluded.embedding_profile_id,chunking_profile_id=excluded.chunking_profile_id,
                    parser_version=excluded.parser_version,chunk_count=excluded.chunk_count,published_at=now()
                    """).params(Map.of("id", job.get("document_version_id"), "attempt", job.get("attempt"), "hash", job.get("text_hash"),
                            "profile", properties.embeddingProfile(), "chunking", result.get("chunking_profile_id"),
                            "parser", result.get("parser_version"), "count", chunks.size())).update();
            jdbc.sql("UPDATE document_processing SET status='COMPLETED',source_status='READY',index_status='READY',error_code=null,lease_token=null,lease_until=null,updated_at=now() WHERE document_version_id=:id AND attempt=:attempt AND lease_token=:lease")
                    .params(fence(job)).update();
            jdbc.sql("UPDATE document_version SET parse_status='OK',parse_error=null WHERE id=:id").param("id", job.get("document_version_id")).update();
        });
    }

    private List<Map<String, Object>> validateChunks(String source, Object value) {
        var raw = array(value); if (raw.isEmpty() || raw.size() > 2000) throw invalid("invalid_chunk_count");
        String[] lines = source.split("\n", -1); boolean[] covered = new boolean[lines.length];
        var chunks = new ArrayList<Map<String, Object>>(); var keys = new HashSet<String>(); var numbers = new HashSet<Integer>();
        for (Object item : raw) {
            var chunk = object(item); int no = integer(chunk.get("chunk_no"));
            if (no < 0 || no >= raw.size() || !numbers.add(no) || !keys.add(text(chunk.get("chunk_key")))) throw invalid("invalid_chunk_keys");
            var locator = object(chunk.get("locator")); int start = integer(locator.get("start_line")), end = integer(locator.get("end_line"));
            if (!"text_lines".equals(locator.get("type")) || start < 1 || end < start || end > lines.length) throw invalid("invalid_chunk_locator");
            var restored = new StringBuilder();
            for (int i = start - 1; i < end; i++) {
                if (covered[i] && !lines[i].isBlank()) throw invalid("overlapping_source_ranges");
                covered[i] = true; restored.append(lines[i]); if (i < lines.length - 1) restored.append('\n');
            }
            if (!restored.toString().equals(text(chunk.get("text")))) throw invalid("invalid_chunk_text");
            chunks.add(chunk);
        }
        for (int i = 0; i < lines.length; i++) if (!covered[i] && !lines[i].isBlank()) throw invalid("missing_source_ranges");
        chunks.sort(Comparator.comparingInt(c -> integer(c.get("chunk_no")))); return chunks;
    }
    private void configured() {
        if (properties.embeddingProfile() == null || properties.embeddingModel() == null
                || properties.embeddingProfile().equals("unconfigured") || properties.embeddingModel().equals("unconfigured")) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "embedding_profile_not_configured", "임베딩 프로필을 설정해야 합니다.");
        }
    }
    private Map<String, String> vectors(Map<String, Object> response, String purpose) {
        if (!"MODEL".equals(response.get("origin"))) throw invalid("invalid_embedding_origin");
        var result = object(response.get("result"));
        if (!properties.embeddingProfile().equals(result.get("embedding_profile_id")) || !purpose.equals(result.get("purpose"))
                || !properties.embeddingModel().equals(result.get("model")) || integer(result.get("dimension")) != properties.embeddingDimension()) throw invalid("embedding_profile_mismatch");
        var vectors = new HashMap<String, String>();
        for (var value : array(result.get("vectors"))) {
            var item = object(value); String key = text(item.get("key")); var values = array(item.get("embedding"));
            if (values.size() != properties.embeddingDimension()) throw invalid("invalid_embedding_dimension");
            double norm = 0; var numbers = new ArrayList<Double>();
            for (var v : values) {
                if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) throw invalid("invalid_embedding_vector");
                double d = n.doubleValue(); norm += d * d; numbers.add(d);
            }
            if (!Double.isFinite(norm) || norm == 0 || vectors.put(key, JSON.writeValueAsString(numbers)) != null) throw invalid("invalid_embedding_vector");
        }
        return vectors;
    }
    private Map<String, Object> call(Map<String, Object> job, Map<String, Object> input, boolean embedding) {
        long start = System.nanoTime();
        try {
            var response = embedding ? ai.documentEmbeddings(input) : ai.documentChunks(input);
            usage(job, response, (System.nanoTime() - start) / 1_000_000);
            return Objects.requireNonNull(response);
        } catch (RestClientResponseException error) {
            try { usage(job, error.getResponseBodyAs(Map.class), (System.nanoTime() - start) / 1_000_000); }
            catch (RuntimeException ignored) { LoggerFactory.getLogger(getClass()).warn("AI error usage unavailable"); }
            throw error;
        }
    }
    private void usage(Map<String, Object> job, Map<String, Object> response, long observedMillis) {
        if (response == null || !(response.get("usage") instanceof List<?> entries)) return;
        for (Object value : entries) {
            var item = object(value); boolean known = Boolean.TRUE.equals(item.get("tokens_known"));
            var params = new HashMap<String, Object>();
            params.put("project", job.get("project_id")); params.put("key", text(item.get("request_key")));
            params.put("feature", Objects.toString(item.get("feature"), "EMBEDDING"));
            params.put("provider", Objects.toString(item.get("provider"), "unspecified")); params.put("model", text(item.get("model")));
            params.put("known", known); params.put("input", known ? integer(item.get("input_tokens")) : null);
            params.put("output", known ? integer(item.get("output_tokens")) : null);
            params.put("latency", item.getOrDefault("latency_ms", Math.min(Integer.MAX_VALUE, observedMillis)));
            params.put("failed", Boolean.TRUE.equals(item.get("failed")));
            params.put("cache", item.get("cache_tokens"));
            params.put("cost", item.get("estimated_cost_amount") == null ? null : new java.math.BigDecimal(item.get("estimated_cost_amount").toString()));
            params.put("currency", item.get("estimated_cost_amount") == null ? null : text(item.get("currency")));
            jdbc.sql("INSERT INTO llm_usage(project_id,feature,request_key,provider,model,tokens_known,input_tokens,output_tokens,cache_tokens,latency_ms,estimated_cost_amount,currency,failed,origin) VALUES(:project,:feature,:key,:provider,:model,:known,:input,:output,:cache,:latency,:cost,:currency,:failed,'model') ON CONFLICT(request_key) DO NOTHING")
                    .params(params).update();
        }
    }
    private void fail(Map<String, Object> job, RuntimeException error) {
        String code = error instanceof ApiException e ? (e.getCode().equals("validation_failed") ? e.getMessage() : e.getCode())
                : error instanceof RestClientResponseException ? "ai_unavailable"
                : error instanceof org.springframework.web.client.ResourceAccessException ? "ai_timeout_or_connection_failed" : "analysis_failed";
        tx.executeWithoutResult(status -> {
            var params = new HashMap<>(fence(job)); params.put("code", code);
            int updated = jdbc.sql("UPDATE document_processing SET status='FAILED',source_status=CASE WHEN source_status='READY' THEN 'READY' ELSE 'FAILED' END,index_status='FAILED',error_code=:code,lease_token=null,lease_until=null,updated_at=now() WHERE document_version_id=:id AND attempt=:attempt AND lease_token=:lease AND status='RUNNING'")
                    .params(params).update();
            if (updated == 1) jdbc.sql("UPDATE document_version SET parse_status=CASE WHEN :source_ready THEN 'OK' ELSE 'FAILED' END,parse_error=:code WHERE id=:id")
                    .param("source_ready", rows("SELECT source_status FROM document_processing WHERE document_version_id=:id", Map.of("id", job.get("document_version_id"))).getFirst().get("source_status").equals("READY"))
                    .param("code", code).param("id", job.get("document_version_id")).update();
        });
    }

    public Map<String, Object> search(long project, String query, String domain, String kind, String cursor, int limit) {
        long start = System.nanoTime(); configured();
        Long baseline = jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:project").param("project", project).query(Long.class).optional().orElse(null);
        if (baseline == null) throw conflict("index_not_ready");
        int missing = jdbc.sql("""
                SELECT count(*) FROM baseline_document b LEFT JOIN document_index_manifest m
                  ON m.document_version_id=b.document_version_id AND m.embedding_profile_id=:profile
                WHERE b.baseline_id=:baseline AND m.document_version_id IS NULL
                """).param("baseline", baseline).param("profile", properties.embeddingProfile()).query(Integer.class).single();
        if (missing > 0) throw conflict("index_not_ready");
        String signature = hash(JSON.writeValueAsBytes(Arrays.asList(project, baseline, query, domain, kind, properties.embeddingProfile())));
        int offset = 0;
        if (cursor != null) {
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 2 || !signature.equals(parts[0])) throw new IllegalArgumentException();
                offset = Integer.parseInt(parts[1]); if (offset < 0 || offset > 10000) throw new IllegalArgumentException();
            } catch (RuntimeException e) { throw invalid("검색 cursor가 만료되었거나 유효하지 않습니다."); }
        }
        var queryInput = Map.<String, Object>of("project_id", Long.toString(project), "request_key", "document-query-" + UUID.randomUUID(),
                "embedding_profile_id", properties.embeddingProfile(), "purpose", "QUERY", "model", properties.embeddingModel(),
                "dimension", properties.embeddingDimension(), "texts", List.of(Map.of("key", "query", "text", query)));
        var embeddings = vectors(call(Map.of("project_id", project), queryInput, true), "QUERY");
        if (!embeddings.keySet().equals(Set.of("query"))) throw invalid("invalid_embedding_keys");
        String sql = """
                SELECT c.id,c.text,c.locator::text AS locator,d.title,d.id AS document_id,v.id AS document_version_id,v.version_no,
                1-(c.embedding <=> cast(:vector AS vector)) AS score FROM baseline_document b
                JOIN document d ON d.id=b.document_id AND d.project_id=:project
                JOIN document_version v ON v.id=b.document_version_id AND v.document_id=d.id
                JOIN document_index_manifest m ON m.document_version_id=v.id AND m.embedding_profile_id=:profile
                JOIN document_chunk c ON c.document_version_id=v.id
                WHERE b.baseline_id=:baseline
                """;
        var params = new HashMap<String, Object>(Map.of("project", project, "baseline", baseline, "profile", properties.embeddingProfile(),
                "vector", embeddings.get("query"), "limit", limit + 1, "offset", offset));
        if (domain != null) { sql += " AND d.domain=:domain"; params.put("domain", domain); }
        if (kind != null) { sql += " AND d.type=:kind"; params.put("kind", kind.equals("REQUIREMENTS") ? "REQ_SPEC" : kind); }
        var found = rows(sql + " ORDER BY c.embedding <=> cast(:vector AS vector),c.id LIMIT :limit OFFSET :offset", params);
        Long finalBaseline = jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:project").param("project", project).query(Long.class).single();
        if (!baseline.equals(finalBaseline)) throw conflict("baseline_changed");
        boolean more = found.size() > limit; int nextOffset = offset + limit;
        var results = found.subList(0, Math.min(limit, found.size())).stream().map(row -> {
            var locator = object(JSON.readValue(row.get("locator").toString(), Map.class));
            return Map.<String, Object>of("type", "DOCUMENT_CHUNK", "id", row.get("id").toString(), "title", row.get("title"),
                    "snippet", row.get("text"), "score", row.get("score"), "source", Map.of("document_id", row.get("document_id").toString(),
                    "document_version_id", row.get("document_version_id").toString(), "version_no", row.get("version_no"),
                    "location", locator.get("start_line") + "~" + locator.get("end_line") + "행"));
        }).toList();
        var result = page(results, more, more ? Base64.getUrlEncoder().withoutPadding().encodeToString((signature + "|" + nextOffset).getBytes(StandardCharsets.UTF_8)) : null);
        result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000); return result;
    }
}
