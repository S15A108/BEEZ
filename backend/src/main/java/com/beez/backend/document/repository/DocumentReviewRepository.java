package com.beez.backend.document.repository;

import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.id;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL only. The service owns authorization, state transitions and transaction boundaries. */
@Repository
public class DocumentReviewRepository {
    private final JdbcClient jdbc;
    public DocumentReviewRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    private List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query().listOfRows();
    }

    public Optional<Map<String, Object>> workingCopy(long project, long document) {
        return rows("""
                SELECT w.*,d.project_id,d.type FROM document_working_copy w JOIN document d ON d.id=w.document_id
                WHERE d.project_id=:project AND d.id=:document
                """, Map.of("project", project, "document", document)).stream().findFirst();
    }

    public boolean lockMember(long project, long account, boolean leader) {
        var p = Map.of("project", project, "account", account);
        if (rows("SELECT id FROM project WHERE id=:project AND archived_at IS NULL FOR UPDATE", p).isEmpty()) return false;
        return currentRole(project, account).filter(role -> !leader || "LEADER".equals(role)).isPresent();
    }

    public Optional<String> currentRole(long project, long account) {
        return rows("""
                SELECT m.role FROM project_member m JOIN account a ON a.id=m.account_id JOIN project p ON p.id=m.project_id
                WHERE m.project_id=:project AND m.account_id=:account AND m.active=true AND a.deleted_at IS NULL AND p.archived_at IS NULL
                """, Map.of("project", project, "account", account)).stream().findFirst().map(row -> row.get("role").toString());
    }

    public boolean hasBaseline(long project) {
        return !rows("SELECT id FROM baseline WHERE project_id=:project LIMIT 1", Map.of("project", project)).isEmpty();
    }

    public void initialize(long document, long version, long owner, String filename) {
        jdbc.sql("""
                INSERT INTO document_working_copy(document_id,owner_account_id,initial_version_id,content_text,file_name,storage_key)
                SELECT document_id,:owner,id,content_text,:filename,storage_key
                FROM document_version WHERE id=:version AND document_id=:document
                """).params(Map.of("document", document, "version", version, "owner", owner, "filename", filename)).update();
    }

    public void edit(long document, String content, String name) {
        jdbc.sql("INSERT INTO document_original_cleanup(storage_key) SELECT storage_key FROM document_working_copy WHERE document_id=:document AND storage_key IS NOT NULL ON CONFLICT DO NOTHING")
                .param("document", document).update();
        jdbc.sql("""
                UPDATE document_working_copy SET content_text=:content,file_name=:name,storage_key=null,
                  revision=revision+1,status='EDITING',updated_at=now() WHERE document_id=:document
                """).params(Map.of("document", document, "content", content, "name", name)).update();
    }

    public List<Map<String, Object>> references(long project, long document) {
        return rows("""
                SELECT DISTINCT ON (d.id) d.id AS document_id,d.type,v.id AS document_version_id,v.version_no,v.content_text,v.content_hash
                FROM document d JOIN document_version v ON v.document_id=d.id
                WHERE d.project_id=:project AND v.status='APPROVED'
                ORDER BY d.id,v.version_no DESC
                """, Map.of("project", project, "document", document));
    }

    public void workingOriginal(long document, String key) {
        jdbc.sql("UPDATE document_working_copy SET storage_key=:key WHERE document_id=:document")
                .params(Map.of("document", document, "key", key)).update();
    }

    public long saveReview(long document, long revision, String referenceHash, Object input, Object result, Object usage) {
        return jdbc.sql("""
                INSERT INTO document_bee_review(document_id,input_revision,resolved_revision,reference_hash,input,result,usage)
                VALUES(:document,:revision,:revision,:hash,cast(:input AS jsonb),cast(:result AS jsonb),cast(:usage AS jsonb)) RETURNING id
                """).params(Map.of("document", document, "revision", revision, "hash", referenceHash,
                        "input", com.beez.backend.designitem.support.DesignJson.JSON.writeValueAsString(input),
                        "result", canonical(result), "usage", canonical(usage))).query(Long.class).single();
    }

    public void saveSuggestion(long review, String explanation, String original, String replacement) {
        jdbc.sql("""
                INSERT INTO document_bee_suggestion(review_id,explanation,original_text,replacement_text)
                VALUES(:review,:explanation,:original,:replacement)
                """).params(Map.of("review", review, "explanation", explanation, "original", original, "replacement", replacement)).update();
    }

    public Optional<Map<String, Object>> review(long document, Long review) {
        return rows("SELECT * FROM document_bee_review WHERE document_id=:document" +
                        (review == null ? " ORDER BY id DESC LIMIT 1" : " AND id=:review"),
                review == null ? Map.of("document", document) : Map.of("document", document, "review", review)).stream().findFirst();
    }

    public List<Map<String, Object>> suggestions(long review) {
        return rows("SELECT * FROM document_bee_suggestion WHERE review_id=:review ORDER BY id", Map.of("review", review));
    }

    public void decideSuggestion(long suggestion, String decision, long review, long revision) {
        jdbc.sql("UPDATE document_bee_suggestion SET decision=:decision,decided_at=now() WHERE id=:id")
                .params(Map.of("id", suggestion, "decision", decision)).update();
        jdbc.sql("UPDATE document_bee_review SET resolved_revision=:revision WHERE id=:review")
                .params(Map.of("review", review, "revision", revision)).update();
    }

    public List<Map<String, Object>> submissions(long project, Long document, String status) {
        var p = new java.util.HashMap<String, Object>();
        p.put("project", project);
        String sql = "SELECT s.* FROM document_review_submission s JOIN document d ON d.id=s.document_id WHERE d.project_id=:project";
        if (document != null) { sql += " AND s.document_id=:document"; p.put("document", document); }
        if (status != null) { sql += " AND s.status=:status"; p.put("status", status); }
        return rows(sql + " ORDER BY s.id DESC", p);
    }

    public Optional<Map<String, Object>> submission(long project, long document, long submission) {
        return rows("""
                SELECT s.* FROM document_review_submission s JOIN document d ON d.id=s.document_id
                WHERE d.project_id=:project AND s.document_id=:document AND s.id=:submission
                """, Map.of("project", project, "document", document, "submission", submission)).stream().findFirst();
    }

    public String submittedContent(long version) {
        return jdbc.sql("SELECT content_text FROM document_version WHERE id=:version")
                .param("version", version).query(String.class).single();
    }

    public long submit(Map<String, Object> copy, long review, String key, String hash, String textHash) {
        long document = id(copy, "document_id");
        long version;
        if (submissions(id(copy, "project_id"), document, null).isEmpty()) {
            version = id(copy, "initial_version_id");
            jdbc.sql("INSERT INTO document_original_cleanup(storage_key) SELECT storage_key FROM document_version WHERE id=:version ON CONFLICT DO NOTHING")
                    .param("version", version).update();
            // Lock processing first, matching the index worker's processing -> version order.
            jdbc.sql("SELECT document_version_id FROM document_processing WHERE document_version_id=:version FOR UPDATE")
                    .param("version", version).query(Long.class).optional();
            jdbc.sql("""
                    UPDATE document_version SET storage_key=:key,content_hash=:hash,content_text=:content,
                      parse_status='PENDING',parse_error=null,updated_at=now(),lock_version=lock_version+1 WHERE id=:version AND status='DRAFT'
                    """).params(Map.of("key", key, "hash", hash, "content", copy.get("content_text"), "version", version)).update();
            // Replace the indexing lease fence before changing the source of the unpublished draft.
            jdbc.sql("""
                    UPDATE document_processing SET status='PENDING',attempt=attempt+1,automatic_recoveries=0,
                      source_status='PENDING',index_status='PENDING',text_hash=:hash,error_code=null,
                      lease_token=null,lease_until=null,updated_at=now() WHERE document_version_id=:version
                    """).params(Map.of("version", version, "hash", textHash)).update();
        } else {
            version = jdbc.sql("""
                    INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,parent_version_id)
                    SELECT :document,coalesce(max(version_no),0)+1,'DRAFT',:key,:hash,:content,:owner,:parent
                    FROM document_version WHERE document_id=:document RETURNING id
                    """).params(new java.util.HashMap<>(Map.of("document", document, "key", key, "hash", hash,
                            "content", copy.get("content_text"), "owner", copy.get("owner_account_id"),
                            "parent", copy.get("base_version_id") == null ? id(copy, "initial_version_id") : id(copy, "base_version_id")))).query(Long.class).single();
            jdbc.sql("INSERT INTO document_processing(document_version_id,status,text_hash) VALUES(:version,'PENDING',:hash)")
                    .params(Map.of("version", version, "hash", textHash)).update();
        }
        long submission = jdbc.sql("""
                INSERT INTO document_review_submission(document_id,document_version_id,submitted_by,revision,bee_review_id,content_hash)
                VALUES(:document,:version,:owner,:revision,:review,:hash) RETURNING id
                """).params(Map.of("document", document, "version", version, "owner", copy.get("owner_account_id"),
                        "revision", copy.get("revision"), "review", review, "hash", hash)).query(Long.class).single();
        jdbc.sql("INSERT INTO document_original_cleanup(storage_key) SELECT storage_key FROM document_working_copy WHERE document_id=:document AND storage_key IS NOT NULL ON CONFLICT DO NOTHING")
                .param("document", document).update();
        jdbc.sql("UPDATE document_working_copy SET status='SUBMITTED',storage_key=:key,updated_at=now() WHERE document_id=:document")
                .params(Map.of("document", document, "key", key)).update();
        return submission;
    }

    public void decide(Map<String, Object> submission, long actor, String status, String reason) {
        long document = id(submission, "document_id"), version = id(submission, "document_version_id");
        if ("APPROVED".equals(status)) {
            jdbc.sql("UPDATE document_version SET status='SUPERSEDED',updated_at=now() WHERE document_id=:document AND status='APPROVED'")
                    .param("document", document).update();
            jdbc.sql("UPDATE document_version SET status='APPROVED',approved_by=:actor,approved_at=now(),updated_at=now(),lock_version=lock_version+1 WHERE id=:version")
                    .params(Map.of("actor", actor, "version", version)).update();
        } else {
            jdbc.sql("UPDATE document_version SET status='REJECTED',updated_at=now(),lock_version=lock_version+1 WHERE id=:version")
                    .param("version", version).update();
        }
        var p = new java.util.HashMap<String, Object>(Map.of("id", id(submission, "id"), "actor", actor, "status", status));
        p.put("reason", reason);
        jdbc.sql("UPDATE document_review_submission SET status=:status,decided_by=:actor,decided_at=now(),rejection_reason=:reason WHERE id=:id")
                .params(p).update();
        jdbc.sql("UPDATE document_working_copy SET status=:status,updated_at=now() WHERE document_id=:document")
                .params(Map.of("status", status, "document", document)).update();
        if ("REJECTED".equals(status)) {
            jdbc.sql("UPDATE document_working_copy SET revision=revision+1 WHERE document_id=:document")
                    .param("document", document).update();
        } else {
            jdbc.sql("UPDATE document_working_copy SET base_version_id=:version WHERE document_id=:document")
                    .params(Map.of("document", document, "version", version)).update();
        }
    }

    public Optional<String> replay(long project, long actor, String operation, String key, String fingerprint) {
        var p = Map.of("project", project, "actor", actor, "operation", operation, "key", key);
        jdbc.sql("DELETE FROM document_http_command WHERE project_id=:project AND account_id=:actor AND operation=:operation AND request_key=:key AND expires_at<=now()")
                .params(p).update();
        return rows("SELECT fingerprint,response FROM document_http_command WHERE project_id=:project AND account_id=:actor AND operation=:operation AND request_key=:key", p)
                .stream().findFirst().map(row -> {
                    if (!fingerprint.equals(row.get("fingerprint"))) throw com.beez.backend.designitem.support.DesignJson.conflict("idempotency_conflict");
                    return row.get("response").toString();
                });
    }

    public void remember(long project, long actor, String operation, String key, String fingerprint, Object response) {
        jdbc.sql("""
                INSERT INTO document_http_command(project_id,account_id,operation,request_key,fingerprint,response)
                VALUES(:project,:actor,:operation,:key,:fingerprint,cast(:response AS jsonb))
                """).params(Map.of("project", project, "actor", actor, "operation", operation, "key", key,
                        "fingerprint", fingerprint, "response", canonical(response))).update();
    }

    public Optional<Map<String, Object>> reviewRequest(long document, long account, String key) {
        // Expired in-flight work is failed, never silently reissued to a paid provider.
        jdbc.sql("UPDATE document_bee_review_request SET status='FAILED',error_code='review_timeout' WHERE document_id=:document AND account_id=:account AND request_key=:key AND status='RUNNING' AND created_at<now()-interval '10 minutes'")
                .params(Map.of("document", document, "account", account, "key", key)).update();
        return rows("SELECT * FROM document_bee_review_request WHERE document_id=:document AND account_id=:account AND request_key=:key",
                Map.of("document", document, "account", account, "key", key)).stream().findFirst();
    }

    public void startReview(long document, long account, String key, String fingerprint) {
        jdbc.sql("INSERT INTO document_bee_review_request(document_id,account_id,request_key,fingerprint,status) VALUES(:document,:account,:key,:fingerprint,'RUNNING')")
                .params(Map.of("document", document, "account", account, "key", key, "fingerprint", fingerprint)).update();
    }

    public boolean finishReview(long document, long account, String key, Long review, String error, Object usage) {
        var p = new java.util.HashMap<String, Object>();
        p.put("document", document); p.put("account", account); p.put("key", key); p.put("review", review);
        p.put("error", error); p.put("usage", canonical(usage)); p.put("status", error == null ? "COMPLETED" : "FAILED");
        return jdbc.sql("UPDATE document_bee_review_request SET status=:status,review_id=:review,error_code=:error,usage=cast(:usage AS jsonb) WHERE document_id=:document AND account_id=:account AND request_key=:key AND status='RUNNING'")
                .params(p).update() == 1;
    }

    public List<String> cleanupKeys() {
        return jdbc.sql("SELECT storage_key FROM document_original_cleanup ORDER BY created_at LIMIT 20").query(String.class).list();
    }

    public boolean referencedOriginal(String key) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_version WHERE storage_key=:key) OR EXISTS(SELECT 1 FROM document_working_copy WHERE storage_key=:key)")
                .param("key", key).query(Boolean.class).single();
    }

    public void cleanedOriginal(String key) {
        jdbc.sql("DELETE FROM document_original_cleanup WHERE storage_key=:key").param("key", key).update();
    }

    public void recordUsage(long project, List<Map<String, Object>> entries) {
        for (var entry : entries) {
            var p = new java.util.HashMap<>(entry);
            p.put("project", project);
            p.putIfAbsent("provider", "unspecified");
            p.putIfAbsent("input_tokens", null); p.putIfAbsent("output_tokens", null); p.putIfAbsent("cache_tokens", null);
            p.putIfAbsent("estimated_cost_amount", null); p.putIfAbsent("currency", null);
            jdbc.sql("""
                    INSERT INTO llm_usage(project_id,feature,request_key,provider,model,tokens_known,input_tokens,output_tokens,
                      cache_tokens,latency_ms,estimated_cost_amount,currency,failed,origin)
                    VALUES(:project,:feature,:request_key,:provider,:model,:tokens_known,:input_tokens,:output_tokens,
                      :cache_tokens,:latency_ms,:estimated_cost_amount,:currency,:failed,'model')
                    ON CONFLICT(request_key) DO NOTHING
                    """).params(p).update();
        }
    }
}
