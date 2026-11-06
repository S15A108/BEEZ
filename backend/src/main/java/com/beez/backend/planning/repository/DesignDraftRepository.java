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
        return jdbc.sql("SELECT id,project_id,document_version_id,status,result_meta,error_code,retryable,created_at,updated_at FROM design_draft_job WHERE project_id=:project"
                + filter + " ORDER BY id DESC LIMIT :limit").params(p).query().listOfRows();
    }

    // ---- 후보 ----

    public void insertCandidate(Map<String, Object> c) {
        jdbc.sql("""
                INSERT INTO design_draft_candidate(job_id,project_id,candidate_key,change_kind,kind,base_design_item_version_id,base_status,
                  before_content,before_hash,proposed_after,changed_fields,conflicting_fields,sources,answer_refs,support,rationale)
                VALUES(:job,:project,:key,:change,:kind,:base,:baseStatus,cast(:before AS jsonb),:beforeHash,cast(:after AS jsonb),
                  cast(:changed AS jsonb),cast(:conflicting AS jsonb),cast(:sources AS jsonb),cast(:refs AS jsonb),:support,:rationale)
                """).params(c).update();
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
