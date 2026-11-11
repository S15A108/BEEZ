package com.beez.backend.planning.repository;

import static com.beez.backend.designitem.support.DesignJson.canonical;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 답변 반영 설계 초안 생성 작업·생성 후보(PLN-A05)의 저장소. 쓰기는 호출한 서비스의 트랜잭션 안에서 한다. */
@Repository
public class DesignDraftRepository {
    private final JdbcClient jdbc;

    public DesignDraftRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- 접수 입력 ----

    /** 문서 버전이 프로젝트 소속이면 문서·버전 정보를, 아니면 비어 있다. */
    public Optional<Map<String, Object>> documentVersion(long project, long version) {
        return jdbc.sql("""
                SELECT v.id AS document_version_id,v.version_no,v.status,v.content_text,v.content_hash,v.storage_key,
                       d.id AS document_id,d.type,dp.status AS processing_status,dp.text_hash
                  FROM document_version v JOIN document d ON d.id=v.document_id
                  LEFT JOIN document_processing dp ON dp.document_version_id=v.id
                 WHERE v.id=:version AND d.project_id=:project
                """).param("version", version).param("project", project).query().listOfRows().stream().findFirst();
    }

    /**
     * 이 문서 버전에서 나온 현재 설계 항목을 항목당 한 버전만: 사람이 고친 현재 DRAFT 가 있으면 그것, 없으면 가장 최근 APPROVED.
     * 출처가 없는 수동 항목은 포함하지 않는다(134 계약 §1).
     */
    public List<Map<String, Object>> existingItems(long project, long version) {
        return jdbc.sql("""
                SELECT DISTINCT ON (i.id) v.id AS design_item_version_id,i.id AS design_item_id,i.key,i.kind,v.version_no,v.status,
                       v.schema_version,v.content,v.source_locator,v.source_document_version_id,s.version_no AS source_version_no,
                       s.document_id AS source_document_id
                  FROM design_item i JOIN design_item_version v ON v.design_item_id=i.id
                  JOIN document_version s ON s.id=v.source_document_version_id
                 WHERE i.project_id=:project AND i.retired_at IS NULL AND v.status IN ('DRAFT','APPROVED')
                   AND v.source_document_version_id=:version
                 ORDER BY i.id,(v.status='DRAFT') DESC,v.version_no DESC
                """).param("project", project).param("version", version).query().listOfRows();
    }

    /** 이 문서 버전의 질문(SUPERSEDED·키 없는 예전 질문 제외). 답변 상태와 함께 AI 입력으로 쓴다. */
    public List<Map<String, Object>> questions(long project, long version) {
        return jdbc.sql("""
                SELECT * FROM planning_question
                 WHERE project_id=:project AND document_version_id=:version AND status<>'SUPERSEDED' AND question_key IS NOT NULL
                 ORDER BY rank NULLS LAST,id
                """).param("project", project).param("version", version).query().listOfRows();
    }

    public boolean hasBaseline(long project) {
        return jdbc.sql("SELECT current_baseline_id IS NOT NULL FROM project WHERE id=:project").param("project", project)
                .query(Boolean.class).optional().orElse(false);
    }

    // ---- 작업 ----

    public boolean activeJobExists(long version) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM design_draft_job WHERE document_version_id=:version AND status IN ('PENDING','RUNNING'))")
                .param("version", version).query(Boolean.class).single();
    }

    public long insertJob(long project, long version, long account, String requestKey, Object snapshot, String hash) {
        return jdbc.sql("""
                INSERT INTO design_draft_job(project_id,document_version_id,requested_by,request_key,status,input_snapshot,input_hash)
                VALUES(:project,:version,:account,:key,'PENDING',cast(:snapshot AS jsonb),:hash) RETURNING id
                """).param("project", project).param("version", version).param("account", account).param("key", requestKey)
                .param("snapshot", canonical(snapshot)).param("hash", hash).query(Long.class).single();
    }

    /** 처리할 작업 하나를 RUNNING 으로 잡는다(대기 중이거나 lease 가 만료된 것). 잡은 행을 돌려준다. */
    public Optional<Map<String, Object>> claim(int leaseSeconds) {
        return jdbc.sql("""
                UPDATE design_draft_job SET status='RUNNING',lease_until=now()+make_interval(secs=>:lease),updated_at=now()
                 WHERE id=(SELECT id FROM design_draft_job WHERE status='PENDING' OR (status='RUNNING' AND lease_until<now())
                            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED)
                RETURNING *
                """).param("lease", leaseSeconds).query().listOfRows().stream().findFirst();
    }

    /** lease 가 그대로인(다른 워커가 이어받지 않은) 작업만 끝낸다. */
    public boolean finishJob(long id, Object lease, String status, String errorCode, boolean retryable, Object resultMeta, Object usage) {
        return jdbc.sql("""
                UPDATE design_draft_job SET status=:status,error_code=:error,retryable=:retryable,result_meta=cast(:meta AS jsonb),
                  usage=cast(:usage AS jsonb),lease_until=null,updated_at=now()
                 WHERE id=:id AND status='RUNNING' AND lease_until=:lease
                """).param("status", status).param("error", errorCode, Types.VARCHAR).param("retryable", retryable)
                .param("meta", resultMeta == null ? null : canonical(resultMeta), Types.VARCHAR)
                .param("usage", canonical(usage == null ? List.of() : usage)).param("id", id).param("lease", lease).update() == 1;
    }

    public boolean jobStillRunning(long id, Object lease) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM design_draft_job WHERE id=:id AND status='RUNNING' AND lease_until=:lease)")
                .param("id", id).param("lease", lease).query(Boolean.class).single();
    }

    public List<Map<String, Object>> jobs(long project, Long version, int limit) {
        var p = new HashMap<String, Object>();
        p.put("project", project); p.put("limit", limit);
        String filter = "";
        if (version != null) { filter = " AND document_version_id=:version"; p.put("version", version); }
        return jdbc.sql("SELECT id,project_id,document_version_id,status,result_meta,error_code,retryable,created_at,updated_at,mode,trigger_kind,attempt_no FROM design_draft_job WHERE project_id=:project"
                + filter + " ORDER BY id DESC LIMIT :limit").params(p).query().listOfRows();
    }

    // ---- 후보 ----

    public void insertCandidate(Map<String, Object> c) {
        var p = new HashMap<>(c);
        p.putIfAbsent("partNo", null); p.putIfAbsent("fingerprint", null); p.putIfAbsent("overlapKind", null); p.putIfAbsent("overlapRef", null);
        jdbc.sql("""
                INSERT INTO design_draft_candidate(job_id,project_id,candidate_key,change_kind,kind,base_design_item_version_id,base_status,
                  before_content,before_hash,proposed_after,changed_fields,conflicting_fields,sources,answer_refs,support,rationale,
                  part_no,fingerprint,overlap_kind,overlap_ref)
                VALUES(:job,:project,:key,:change,:kind,:base,:baseStatus,cast(:before AS jsonb),:beforeHash,cast(:after AS jsonb),
                  cast(:changed AS jsonb),cast(:conflicting AS jsonb),cast(:sources AS jsonb),cast(:refs AS jsonb),:support,:rationale,
                  :partNo,:fingerprint,:overlapKind,:overlapRef)
                """).params(p).update();
    }

    // ---- 문서 단독 모드(S15P31A108-389) ----

    public long insertDocumentOnlyJob(long project, long version, long account, String requestKey, Object snapshot, String hash, String trigger) {
        return jdbc.sql("""
                INSERT INTO design_draft_job(project_id,document_version_id,requested_by,request_key,status,input_snapshot,input_hash,mode,trigger_kind)
                VALUES(:project,:version,:account,:key,'PENDING',cast(:snapshot AS jsonb),:hash,'DOCUMENT_ONLY',:trigger) RETURNING id
                """).param("project", project).param("version", version).param("account", account).param("key", requestKey)
                .param("snapshot", canonical(snapshot)).param("hash", hash).param("trigger", trigger).query(Long.class).single();
    }

    public void insertPart(long job, int partNo, int start, int end) {
        jdbc.sql("INSERT INTO design_draft_job_part(job_id,part_no,start_line,end_line) VALUES(:job,:no,:start,:end)")
                .param("job", job).param("no", partNo).param("start", start).param("end", end).update();
    }

    public List<Map<String, Object>> parts(long job) {
        return jdbc.sql("SELECT * FROM design_draft_job_part WHERE job_id=:job ORDER BY part_no").param("job", job).query().listOfRows();
    }

    public Optional<Map<String, Object>> nextPendingPart(long job) {
        return jdbc.sql("SELECT * FROM design_draft_job_part WHERE job_id=:job AND status='PENDING' ORDER BY part_no LIMIT 1")
                .param("job", job).query().listOfRows().stream().findFirst();
    }

    public void completePart(long job, int partNo, Object usage) {
        jdbc.sql("UPDATE design_draft_job_part SET status='COMPLETED',error_code=null,retryable=false,usage=cast(:usage AS jsonb),updated_at=now() WHERE job_id=:job AND part_no=:no")
                .param("usage", canonical(usage == null ? List.of() : usage)).param("job", job).param("no", partNo).update();
    }

    public void failPart(long job, int partNo, String code, boolean retryable, Object usage) {
        jdbc.sql("UPDATE design_draft_job_part SET status='FAILED',error_code=:code,retryable=:retryable,usage=cast(:usage AS jsonb),updated_at=now() WHERE job_id=:job AND part_no=:no")
                .param("code", code).param("retryable", retryable).param("usage", canonical(usage == null ? List.of() : usage))
                .param("job", job).param("no", partNo).update();
    }

    /** 긴 작업 중에 lease 를 연장한다. 아직 내 것일 때만 새 lease 를 돌려준다. */
    public Optional<Object> renewLease(long id, Object lease, int seconds) {
        return jdbc.sql("UPDATE design_draft_job SET lease_until=now()+make_interval(secs=>:lease),updated_at=now() WHERE id=:id AND status='RUNNING' AND lease_until=:old RETURNING lease_until")
                .param("lease", seconds).param("id", id).param("old", lease).query().listOfRows().stream().findFirst().map(row -> row.get("lease_until"));
    }

    public Optional<Map<String, Object>> latestDocumentOnlyJob(long version) {
        return jdbc.sql("SELECT * FROM design_draft_job WHERE document_version_id=:version AND mode='DOCUMENT_ONLY' ORDER BY id DESC LIMIT 1")
                .param("version", version).query().listOfRows().stream().findFirst();
    }

    /** 실패한 작업을 다시 대기시키고 실패한 부분만 되돌린다. 성공한 부분과 그 후보는 그대로 둔다. */
    public void retryFailedJob(long id) {
        jdbc.sql("UPDATE design_draft_job_part SET status='PENDING',error_code=null,retryable=false,updated_at=now() WHERE job_id=:id AND status='FAILED'")
                .param("id", id).update();
        jdbc.sql("UPDATE design_draft_job SET status='PENDING',error_code=null,retryable=false,attempt_no=attempt_no+1,lease_until=null,updated_at=now() WHERE id=:id AND status='FAILED'")
                .param("id", id).update();
    }

    public boolean hasCandidateKey(long job, String key) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM design_draft_candidate WHERE job_id=:job AND candidate_key=:key)")
                .param("job", job).param("key", key).query(Boolean.class).single();
    }

    /** 이 프로젝트에서 지금 살아 있는 설계 항목(항목당 최신 DRAFT/APPROVED 한 버전). 지문 비교용. */
    public List<Map<String, Object>> liveItems(long project) {
        return jdbc.sql("""
                SELECT DISTINCT ON (i.id) v.id AS design_item_version_id,i.kind,v.content
                  FROM design_item i JOIN design_item_version v ON v.design_item_id=i.id
                 WHERE i.project_id=:project AND i.retired_at IS NULL AND v.status IN ('DRAFT','APPROVED')
                 ORDER BY i.id,(v.status='DRAFT') DESC,v.version_no DESC
                """).param("project", project).query().listOfRows();
    }

    /** 이미 채택하거나 폐기한 후보의 지문. */
    public List<Map<String, Object>> decidedFingerprints(long project) {
        return jdbc.sql("""
                SELECT id,fingerprint,status FROM design_draft_candidate
                 WHERE project_id=:project AND fingerprint IS NOT NULL AND status IN ('ADOPTED','DISCARDED') ORDER BY id
                """).param("project", project).query().listOfRows();
    }

    /** 자동 접수 대상: 기준이 없고 4종 문서가 모두 최신 승인본(작성 중 사본 없음)인 프로젝트의, 아직 자동 접수 안 된 버전. */
    public List<Map<String, Object>> autoTargets(int limit) {
        return jdbc.sql("""
                WITH latest AS (
                  SELECT DISTINCT ON (d.id) d.project_id,d.id AS document_id,d.type,v.id AS version_id
                    FROM document d JOIN document_version v ON v.document_id=d.id
                   WHERE d.discarded_at IS NULL AND v.status='APPROVED'
                     AND NOT EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id AND w.status<>'APPROVED')
                   ORDER BY d.id,v.version_no DESC)
                SELECT l.project_id,l.version_id,l.type FROM latest l JOIN project p ON p.id=l.project_id
                 WHERE p.current_baseline_id IS NULL AND p.archived_at IS NULL
                   AND (SELECT count(DISTINCT x.type) FROM latest x WHERE x.project_id=l.project_id
                         AND x.type IN ('PLAN','REQ_SPEC','ERD','API_SPEC'))=4
                   AND NOT EXISTS (SELECT 1 FROM design_draft_job j WHERE j.document_version_id=l.version_id AND j.trigger_kind='AUTO')
                 ORDER BY l.project_id,l.version_id LIMIT :limit
                """).param("limit", limit).query().listOfRows();
    }

    public Optional<Long> firstLeader(long project) {
        return jdbc.sql("""
                SELECT m.account_id FROM project_member m JOIN account a ON a.id=m.account_id
                 WHERE m.project_id=:project AND m.role='LEADER' AND m.active=true AND a.deleted_at IS NULL ORDER BY m.account_id LIMIT 1
                """).param("project", project).query(Long.class).optional();
    }

    public java.util.Set<String> jobFingerprints(long job) {
        return new java.util.HashSet<>(jdbc.sql("SELECT fingerprint FROM design_draft_candidate WHERE job_id=:job AND fingerprint IS NOT NULL")
                .param("job", job).query(String.class).list());
    }

    /** 프로젝트의 문서마다 최신 승인본 버전(폐기 문서 제외). */
    public List<Map<String, Object>> latestApprovedVersions(long project) {
        return jdbc.sql("""
                SELECT DISTINCT ON (d.id) v.id AS version_id,d.type FROM document d JOIN document_version v ON v.document_id=d.id
                 WHERE d.project_id=:project AND d.discarded_at IS NULL AND v.status='APPROVED'
                   AND NOT EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id AND w.status<>'APPROVED')
                 ORDER BY d.id,v.version_no DESC
                """).param("project", project).query().listOfRows();
    }

    public boolean autoJobExists(long version) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM design_draft_job WHERE document_version_id=:version AND trigger_kind='AUTO')")
                .param("version", version).query(Boolean.class).single();
    }

    /** 문서 버전이 프로젝트의 최신 승인본(작성 중 사본 없음)인지. 접수 직전 재확인용. */
    public boolean isLatestApproved(long project, long version) {
        return jdbc.sql("""
                SELECT EXISTS(
                  SELECT 1 FROM document_version v JOIN document d ON d.id=v.document_id
                   WHERE v.id=:version AND d.project_id=:project AND d.discarded_at IS NULL AND v.status='APPROVED'
                     AND v.version_no=(SELECT max(v2.version_no) FROM document_version v2 WHERE v2.document_id=d.id AND v2.status='APPROVED')
                     AND NOT EXISTS (SELECT 1 FROM document_working_copy w WHERE w.document_id=d.id AND w.status<>'APPROVED'))
                """).param("project", project).param("version", version).query(Boolean.class).single();
    }

    public List<Map<String, Object>> candidates(long job) {
        return jdbc.sql("SELECT * FROM design_draft_candidate WHERE job_id=:job ORDER BY id").param("job", job).query().listOfRows();
    }

    public Optional<Map<String, Object>> candidateForUpdate(long project, long id) {
        return jdbc.sql("SELECT * FROM design_draft_candidate WHERE project_id=:project AND id=:id FOR UPDATE")
                .param("project", project).param("id", id).query().listOfRows().stream().findFirst();
    }

    public Optional<Map<String, Object>> job(long id) {
        return jdbc.sql("SELECT * FROM design_draft_job WHERE id=:id").param("id", id).query().listOfRows().stream().findFirst();
    }

    public void markAdopted(long id, long itemVersion, long account) {
        jdbc.sql("""
                UPDATE design_draft_candidate SET status='ADOPTED',adopted_item_version_id=:version,decided_by=:account,decided_at=now()
                 WHERE id=:id AND status='PENDING'
                """).param("version", itemVersion).param("account", account).param("id", id).update();
    }

    public void markDiscarded(long id, long account) {
        jdbc.sql("UPDATE design_draft_candidate SET status='DISCARDED',decided_by=:account,decided_at=now() WHERE id=:id AND status='PENDING'")
                .param("account", account).param("id", id).update();
    }

    /** 채택 직전 대조용: 이 설계 항목 버전의 지금 상태. */
    public Optional<Map<String, Object>> itemVersion(long project, long version) {
        return jdbc.sql("""
                SELECT v.id,v.design_item_id,v.status,v.content_hash,v.schema_version,i.kind,i.retired_at,v.source_document_version_id
                  FROM design_item_version v JOIN design_item i ON i.id=v.design_item_id
                 WHERE v.id=:version AND i.project_id=:project
                """).param("version", version).param("project", project).query().listOfRows().stream().findFirst();
    }

    public Optional<Map<String, Object>> currentAnswer(long project, long version, String questionKey) {
        return jdbc.sql("""
                SELECT status,answer_payload FROM planning_question
                 WHERE project_id=:project AND document_version_id=:version AND question_key=:key AND status<>'SUPERSEDED'
                 ORDER BY id DESC LIMIT 1
                """).param("project", project).param("version", version).param("key", questionKey).query().listOfRows().stream().findFirst();
    }

    public Optional<String> processingTextHash(long version) {
        return jdbc.sql("SELECT text_hash FROM document_processing WHERE document_version_id=:version AND status='COMPLETED'")
                .param("version", version).query(String.class).optional();
    }
}
