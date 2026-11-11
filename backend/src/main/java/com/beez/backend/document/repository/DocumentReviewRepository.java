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

    /** 등록 취소한 문서(S15P31A108-382)의 작업본은 없는 것으로 본다. 편집·검토·제출이 모두 404 가 된다. */
    public Optional<Map<String, Object>> workingCopy(long project, long document) {
        return workingCopy(project, document, false);
    }

    public Optional<Map<String, Object>> workingCopy(long project, long document, boolean includeDiscarded) {
        return rows("""
                SELECT w.*,d.project_id,d.type,d.title,d.discarded_at FROM document_working_copy w JOIN document d ON d.id=w.document_id
                WHERE d.project_id=:project AND d.id=:document
                """ + (includeDiscarded ? "" : " AND d.discarded_at IS NULL"),
                Map.of("project", project, "document", document)).stream().findFirst();
    }

    /** 현재 작성 리비전에서 시작한 가장 최근 비비 검토 요청. 고친 뒤(작업본 갱신 이후)에 시작한 요청만 본다. */
    public Optional<Map<String, Object>> latestReviewRequest(long document) {
        return rows("""
                SELECT r.status,r.error_code,r.created_at,r.updated_at,r.lease_until FROM document_bee_review_request r
                JOIN document_working_copy w ON w.document_id=r.document_id
                WHERE r.document_id=:document AND r.created_at>=w.updated_at
                ORDER BY r.created_at DESC LIMIT 1
                """, Map.of("document", document)).stream().findFirst();
    }

    public boolean hasApprovedVersion(long document) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM document_version WHERE document_id=:document AND status IN ('APPROVED','SUPERSEDED'))")
                .param("document", document).query(Boolean.class).single();
    }

    /**
     * 등록 취소(DOC-A25): 문서에 폐기 시각을 남기고 아직 채택되지 않은 버전은 미채택(REJECTED)으로 닫으며,
     * 도는 중인 비비 검토 요청은 실패로 닫아 늦게 도착한 AI 결과가 채택되지 않게 한다. 삭제는 하지 않는다.
     */
    public void discard(long document) {
        jdbc.sql("UPDATE document SET discarded_at=now(),updated_at=now() WHERE id=:document AND discarded_at IS NULL")
                .param("document", document).update();
        jdbc.sql("UPDATE document_version SET status='REJECTED',updated_at=now(),lock_version=lock_version+1 WHERE document_id=:document AND status='DRAFT'")
                .param("document", document).update();
        jdbc.sql("UPDATE document_bee_review_request SET status='FAILED',error_code='document_discarded',input=NULL,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE document_id=:document AND status='RUNNING'")
                .param("document", document).update();
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

    /** 검토 요청 알림을 받을 현재 활성 팀장 계정. 프로젝트당 활성 팀장은 한 명이다 */
    public Optional<Long> leaderAccount(long project) {
        return jdbc.sql("""
                SELECT m.account_id FROM project_member m JOIN account a ON a.id=m.account_id
                WHERE m.project_id=:project AND m.role='LEADER' AND m.active=true AND a.deleted_at IS NULL
                """).param("project", project).query(Long.class).optional();
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

    public void saveSuggestion(long review, Map<?, ?> suggestion) {
        var params = new java.util.HashMap<String, Object>();
        params.put("review", review); params.put("type", suggestion.get("type"));
        params.put("step", suggestion.get("step")); params.put("sources", canonical(suggestion.get("sources")));
        params.put("explanation", suggestion.get("explanation"));
        params.put("original", suggestion.get("original_text")); params.put("replacement", suggestion.get("replacement_text"));
        params.put("category", suggestion.get("category")); params.put("severity", suggestion.get("severity"));
        params.put("decision", "PATCH".equals(suggestion.get("type")) ? "PENDING" : null);
        jdbc.sql("""
                INSERT INTO document_bee_suggestion(review_id,type,step,sources,explanation,original_text,replacement_text,decision,category,severity)
                VALUES(:review,:type,:step,cast(:sources AS jsonb),:explanation,:original,:replacement,:decision,:category,:severity)
                """).params(params).update();
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
        decideSuggestion(suggestion, decision, review, revision, null);
    }

    /** appliedText 는 대화(DOC-A22)에서 받은 다른 대체문으로 반영했을 때만 있다. */
    public void decideSuggestion(long suggestion, String decision, long review, long revision, String appliedText) {
        var p = new java.util.HashMap<String, Object>();
        p.put("id", suggestion); p.put("decision", decision); p.put("applied", appliedText);
        jdbc.sql("UPDATE document_bee_suggestion SET decision=:decision,decided_at=now(),applied_replacement_text=:applied WHERE id=:id")
                .params(p).update();
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

    public Optional<Map<String, Object>> latestSubmission(long project, long document) {
        return rows("""
                SELECT s.* FROM document_review_submission s JOIN document d ON d.id=s.document_id
                WHERE d.project_id=:project AND s.document_id=:document ORDER BY s.id DESC LIMIT 1
                """, Map.of("project", project, "document", document)).stream().findFirst();
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

    /**
     * 접수 뒤 이만큼(분) 지나도록 끝나지 않은 검토는 실패로 본다. 화면 표시(DocumentReviewService.requestView), 같은 키 재요청,
     * 워커 잡기가 모두 이 값을 쓴다. 워커가 지금 붙잡고 있는(lease 가 살아 있는) 작업은 이 값이 지나도 닫지 않는다(S15P31A108-391).
     */
    public static final int STALE_MINUTES = 10;

    public Optional<Map<String, Object>> reviewRequest(long document, long account, String key) {
        // Expired in-flight work is failed, never silently reissued to a paid provider. 워커가 처리 중인 작업은 끝날 때까지 둔다.
        jdbc.sql("""
                UPDATE document_bee_review_request SET status='FAILED',error_code='review_timeout',input=NULL,lease_token=NULL,lease_until=NULL,updated_at=now()
                 WHERE document_id=:document AND account_id=:account AND request_key=:key AND status='RUNNING'
                   AND created_at<now()-make_interval(mins=>:stale) AND (lease_until IS NULL OR lease_until<now())
                """).params(Map.of("document", document, "account", account, "key", key, "stale", STALE_MINUTES)).update();
        return rows("SELECT * FROM document_bee_review_request WHERE document_id=:document AND account_id=:account AND request_key=:key",
                Map.of("document", document, "account", account, "key", key)).stream().findFirst();
    }

    /** 같은 본문·참고 자료(fingerprint)로 아직 도는 검토. 있으면 새 키로 다시 요청해도 AI 를 한 번 더 부르지 않고 이 요청을 기다린다. */
    public Optional<Map<String, Object>> runningReview(long document, long account, String fingerprint) {
        return rows("""
                SELECT * FROM document_bee_review_request WHERE document_id=:document AND account_id=:account AND fingerprint=:fingerprint
                  AND status='RUNNING' AND input IS NOT NULL AND created_at>=now()-make_interval(mins=>:stale)
                ORDER BY created_at DESC LIMIT 1
                """, Map.of("document", document, "account", account, "fingerprint", fingerprint, "stale", STALE_MINUTES)).stream().findFirst();
    }

    /** 검토 작업 접수. 워커가 그대로 AI 에 보낼 입력 스냅샷을 함께 남긴다(S15P31A108-391). */
    public void startReview(long project, long document, long account, String key, String fingerprint, long expected, Object input) {
        jdbc.sql("""
                INSERT INTO document_bee_review_request(document_id,account_id,request_key,fingerprint,status,project_id,expected_revision,input)
                VALUES(:document,:account,:key,:fingerprint,'RUNNING',:project,:expected,cast(:input AS jsonb))
                """).params(Map.of("document", document, "account", account, "key", key, "fingerprint", fingerprint,
                        "project", project, "expected", expected, "input", canonical(input))).update();
    }

    /**
     * 처리할 검토 하나를 잡는다. 아직 아무도 잡지 않았거나 lease 가 지난(워커가 죽은, 예: 배포 재시작) 작업을 고르고, 새 울타리 토큰을
     * 걸어 늦게 돌아온 이전 워커가 끝내지 못하게 한다. 재시도 한도를 다 쓴 작업과, 화면에 이미 실패로 보이는 10분 지난 작업
     * (워커가 꺼져 있던 동안 쌓인 것 등)은 AI 를 부르지 않고 먼저 시간 초과로 닫는다.
     */
    public Optional<Map<String, Object>> claimReview(int leaseSeconds, int maxAttempts) {
        jdbc.sql("""
                UPDATE document_bee_review_request SET status='FAILED',error_code='review_timeout',input=NULL,lease_token=NULL,lease_until=NULL,updated_at=now()
                 WHERE status='RUNNING' AND input IS NOT NULL AND (lease_until IS NULL OR lease_until<now())
                   AND (attempts>=:max OR created_at<now()-make_interval(mins=>:stale))
                """).param("max", maxAttempts).param("stale", STALE_MINUTES).update();
        return jdbc.sql("""
                UPDATE document_bee_review_request r SET lease_token=gen_random_uuid(),lease_until=now()+make_interval(secs=>:lease),
                  attempts=r.attempts+1
                  FROM (SELECT document_id,account_id,request_key FROM document_bee_review_request
                         WHERE status='RUNNING' AND input IS NOT NULL AND attempts<:max AND (lease_until IS NULL OR lease_until<now())
                           AND created_at>=now()-make_interval(mins=>:stale)
                         ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED) c
                 WHERE r.document_id=c.document_id AND r.account_id=c.account_id AND r.request_key=c.request_key
                RETURNING r.*
                """).param("lease", leaseSeconds).param("max", maxAttempts).param("stale", STALE_MINUTES)
                .query().listOfRows().stream().findFirst();
    }

    /** 잡은 울타리 토큰이 그대로인지(다른 워커가 이어받지 않았는지). 저장 트랜잭션 안에서 행을 잠그고 확인한다. */
    public boolean reviewLeaseHeld(long document, long account, String key, Object token) {
        return !rows("""
                SELECT 1 FROM document_bee_review_request WHERE document_id=:document AND account_id=:account AND request_key=:key
                  AND status='RUNNING' AND lease_token=:token FOR UPDATE
                """, Map.of("document", document, "account", account, "key", key, "token", token)).isEmpty();
    }

    /** 울타리 토큰이 그대로인 작업만 끝낸다. 입력 스냅샷은 비운다(완료 입력은 document_bee_review.input 에 남는다). */
    public boolean finishReview(long document, long account, String key, Object token, Long review, String error, Object usage) {
        var p = new java.util.HashMap<String, Object>();
        p.put("document", document); p.put("account", account); p.put("key", key); p.put("review", review); p.put("token", token);
        p.put("error", error); p.put("usage", canonical(usage)); p.put("status", error == null ? "COMPLETED" : "FAILED");
        return jdbc.sql("""
                UPDATE document_bee_review_request SET status=:status,review_id=:review,error_code=:error,usage=cast(:usage AS jsonb),
                  input=NULL,lease_token=NULL,lease_until=NULL,updated_at=now()
                 WHERE document_id=:document AND account_id=:account AND request_key=:key AND status='RUNNING' AND lease_token=:token
                """).params(p).update() == 1;
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
    // ---- DOC-A22 대화 · DOC-A23 직접 요청 ----

    /** 이 검토의 모든 대화(제안별로 묶어 쓴다). */
    public List<Map<String, Object>> messages(long review) {
        return rows("SELECT * FROM document_bee_message WHERE review_id=:review ORDER BY id", Map.of("review", review));
    }

    public List<Map<String, Object>> messagesOf(long suggestion) {
        return rows("SELECT * FROM document_bee_message WHERE suggestion_id=:suggestion ORDER BY id", Map.of("suggestion", suggestion));
    }

    public Map<String, Object> insertMessage(long review, long suggestion, String kind, Long account, String body, String proposed) {
        var p = new java.util.HashMap<String, Object>();
        p.put("review", review); p.put("suggestion", suggestion); p.put("kind", kind); p.put("account", account);
        p.put("body", body); p.put("proposed", proposed);
        return jdbc.sql("""
                INSERT INTO document_bee_message(review_id,suggestion_id,author_kind,author_account_id,body,proposed_text)
                VALUES(:review,:suggestion,:kind,:account,:body,:proposed) RETURNING *
                """).params(p).query().singleRow();
    }

    public int humanMessageCount(long review) {
        return jdbc.sql("SELECT count(*) FROM document_bee_message WHERE review_id=:review AND author_kind='HUMAN'")
                .param("review", review).query(Integer.class).single();
    }

    public int directRequestCount(long review) {
        return jdbc.sql("SELECT count(*) FROM document_bee_suggestion WHERE review_id=:review AND origin='DIRECT_REQUEST'")
                .param("review", review).query(Integer.class).single();
    }

    /** 비비가 직접 요청에 답해 만든 PATCH 제안. 같은 검토에 붙고 처리 전(PENDING)이다. */
    public Map<String, Object> insertRequestedSuggestion(long review, Map<?, ?> suggestion) {
        var p = new java.util.HashMap<String, Object>();
        p.put("review", review); p.put("step", suggestion.get("step")); p.put("sources", canonical(suggestion.get("sources")));
        p.put("explanation", suggestion.get("explanation"));
        p.put("original", suggestion.get("original_text")); p.put("replacement", suggestion.get("replacement_text"));
        return jdbc.sql("""
                INSERT INTO document_bee_suggestion(review_id,type,step,sources,explanation,original_text,replacement_text,decision,origin)
                VALUES(:review,'PATCH',:step,cast(:sources AS jsonb),:explanation,:original,:replacement,'PENDING','DIRECT_REQUEST') RETURNING *
                """).params(p).query().singleRow();
    }

    // ---- DOC-A24 제출 취소 ----

    /** SUBMITTED 인 제출만 취소한다. 취소했으면 true. 결정과 동시에 오면 프로젝트 잠금이 한쪽만 통과시키지만 조건부로 한 번 더 막는다. */
    public boolean withdraw(long submission, long actor) {
        return jdbc.sql("""
                UPDATE document_review_submission SET status='WITHDRAWN',withdrawn_at=now(),withdrawn_by=:actor
                 WHERE id=:id AND status='SUBMITTED'
                """).params(Map.of("id", submission, "actor", actor)).update() == 1;
    }

    /**
     * 취소한 제출의 문서 버전은 반려와 같이 REJECTED 로 두고(제출 이력은 WITHDRAWN 으로 구분), 작성 사본을 EDITING 으로 되돌린 뒤
     * revision 을 올린다. 상태와 revision 은 두 번에 나눠 쓴다 — 제출 중 잠금 트리거가 한 번에 둘을 바꾸는 것을 막는다.
     */
    public void reopenAfterWithdraw(long document, long version) {
        jdbc.sql("UPDATE document_version SET status='REJECTED',updated_at=now(),lock_version=lock_version+1 WHERE id=:version AND status='DRAFT'")
                .param("version", version).update();
        jdbc.sql("UPDATE document_working_copy SET status='EDITING',updated_at=now() WHERE document_id=:document AND status='SUBMITTED'")
                .param("document", document).update();
        jdbc.sql("UPDATE document_working_copy SET revision=revision+1 WHERE document_id=:document").param("document", document).update();
    }
}
