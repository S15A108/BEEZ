package com.beez.backend.planning.repository;

import static com.beez.backend.designitem.support.DesignJson.canonical;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 기획 보완 질문(PLN-A03·A04)과 문서 버전별 질문 생성 실행의 저장소. 모든 쓰기는 호출한 서비스의 트랜잭션 안에서 한다. */
@Repository
public class PlanningQuestionRepository {
    private final JdbcClient jdbc;

    public PlanningQuestionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    // ---- 생성 실행 ----

    /**
     * 질문을 만들 차례인 문서 버전 하나. 분석이 끝났고(COMPLETED) 기준 설계가 아직 없으며, 아직 생성하지 않았거나 분석이
     * 다시 돌았거나(attempt·text_hash 가 달라짐) 멈춘 실행(lease 만료)이다. FAILED 실행은 PLN-A02 가 PENDING 으로 되돌릴 때만 다시 잡는다.
     */
    public Optional<Map<String, Object>> nextRunnable() {
        return jdbc.sql("""
                SELECT dp.document_version_id,dp.attempt,dp.text_hash,v.content_text,v.content_hash,v.storage_key,v.version_no,
                       d.id AS document_id,d.project_id,d.type
                  FROM document_processing dp
                  JOIN document_version v ON v.id=dp.document_version_id
                  JOIN document d ON d.id=v.document_id
                  JOIN project p ON p.id=d.project_id
                  LEFT JOIN planning_question_run r ON r.document_version_id=dp.document_version_id
                 WHERE dp.status='COMPLETED' AND v.status IN ('DRAFT','APPROVED') AND v.content_text IS NOT NULL
                   AND p.archived_at IS NULL AND p.current_baseline_id IS NULL
                   AND (r.document_version_id IS NULL OR r.status='PENDING'
                        OR (r.status IN ('COMPLETED','FAILED') AND (r.analysis_attempt<>dp.attempt OR r.text_hash<>dp.text_hash))
                        OR (r.status='RUNNING' AND r.lease_until<now()))
                 ORDER BY dp.updated_at
                 LIMIT 1 FOR UPDATE OF dp SKIP LOCKED
                """).query().listOfRows().stream().findFirst();
    }

    public void startRun(long version, int attempt, String textHash, String requestKey, int leaseSeconds) {
        jdbc.sql("""
                INSERT INTO planning_question_run(document_version_id,analysis_attempt,text_hash,status,request_key,lease_until)
                VALUES(:version,:attempt,:hash,'RUNNING',:key,now()+make_interval(secs=>:lease))
                ON CONFLICT (document_version_id) DO UPDATE SET analysis_attempt=:attempt,text_hash=:hash,status='RUNNING',
                  request_key=:key,error_code=null,retryable=false,lease_until=now()+make_interval(secs=>:lease),updated_at=now()
                """).param("version", version).param("attempt", attempt).param("hash", textHash).param("key", requestKey)
                .param("lease", leaseSeconds).update();
    }

    /** 이 실행(request_key)이 아직 RUNNING 이고 분석이 그대로일 때만 true. 다른 워커가 이어받았거나 문서가 바뀌었으면 결과를 버린다. */
    public boolean runStillCurrent(long version, String requestKey, String textHash) {
        return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM planning_question_run r JOIN document_processing dp ON dp.document_version_id=r.document_version_id
                               WHERE r.document_version_id=:version AND r.request_key=:key AND r.status='RUNNING' AND dp.text_hash=:hash)
                """).param("version", version).param("key", requestKey).param("hash", textHash).query(Boolean.class).single();
    }

    public boolean finishRun(long version, String requestKey, String status, String errorCode, boolean retryable) {
        return jdbc.sql("""
                UPDATE planning_question_run SET status=:status,error_code=:error,retryable=:retryable,lease_until=null,updated_at=now()
                 WHERE document_version_id=:version AND request_key=:key AND status='RUNNING'
                """).param("status", status).param("error", errorCode, Types.VARCHAR).param("retryable", retryable)
                .param("version", version).param("key", requestKey).update() == 1;
    }

    public Optional<Map<String, Object>> run(long version) {
        return jdbc.sql("SELECT * FROM planning_question_run WHERE document_version_id=:version FOR UPDATE")
                .param("version", version).query().listOfRows().stream().findFirst();
    }

    /** PLN-A02: 질문 생성이 실패한 버전을 다시 대기 상태로 돌린다. 되돌렸으면 true. */
    public boolean resetFailedRun(long version) {
        return jdbc.sql("""
                UPDATE planning_question_run SET status='PENDING',error_code=null,retryable=false,lease_until=null,updated_at=now()
                 WHERE document_version_id=:version AND status='FAILED'
                """).param("version", version).update() == 1;
    }

    // ---- 질문 ----

    /** 이 문서 버전의 SUPERSEDED 아닌 질문 전부(재분석 병합용). */
    public List<Map<String, Object>> liveQuestions(long project, long version) {
        return jdbc.sql("SELECT * FROM planning_question WHERE project_id=:project AND document_version_id=:version AND status<>'SUPERSEDED' ORDER BY id")
                .param("project", project).param("version", version).query().listOfRows();
    }

    public void insertQuestion(Map<String, Object> q) {
        var p = new HashMap<>(q);
        jdbc.sql("""
                INSERT INTO planning_question(project_id,document_version_id,kind,question,origin,status,analysis_request_key,question_key,
                  question_type,answer_format,evidence_status,summary,signal,unverified_reason,evidence,rank)
                VALUES(:project,:version,:kind,:question,:origin,'OPEN',:run,:key,:type,cast(:format AS jsonb),:basis,:summary,:signal,
                  :reason,cast(:evidence AS jsonb),:rank)
                """).params(p).update();
    }

    /** 아직 답하지 않은 질문은 새 결과로 갱신한다(답변된·닫힌 질문은 건드리지 않는다). */
    public void refreshOpenQuestion(long id, Map<String, Object> q) {
        var p = new HashMap<>(q);
        p.put("id", id);
        jdbc.sql("""
                UPDATE planning_question SET kind=:kind,question=:question,origin=:origin,analysis_request_key=:run,question_type=:type,
                  answer_format=cast(:format AS jsonb),evidence_status=:basis,summary=:summary,signal=:signal,unverified_reason=:reason,
                  evidence=cast(:evidence AS jsonb),rank=:rank,updated_at=now()
                 WHERE id=:id AND status='OPEN'
                """).params(p).update();
    }

    public void supersedeOpen(List<Long> ids) {
        for (long id : ids) {
            jdbc.sql("UPDATE planning_question SET status='SUPERSEDED',updated_at=now() WHERE id=:id AND status='OPEN'").param("id", id).update();
        }
    }

    /**
     * 목록 정렬 키(문서 버전, id). 둘 다 만든 뒤에 바뀌지 않는다 — 재분석이 기존 질문의 rank 를 고쳐도 목록 자리는 그대로라,
     * 쪽 사이에 질문이 생기거나 SUPERSEDED 되거나 rank 가 바뀌어도 다음 쪽이 밀리지 않는다.
     */
    public record Position(long versionId, long id) { }

    /**
     * 번호(number)는 필터가 적용된 목록 안의 1부터 이어지는 표시용 순번이다(쪽마다 다시 센다). 쪽 경계는 번호가 아니라
     * 앞 쪽 마지막 질문의 정렬 키({@code after}) 뒤부터다.
     */
    public List<Map<String, Object>> page(long project, Long version, Position after, int limit) {
        var p = new HashMap<String, Object>();
        p.put("project", project); p.put("limit", limit);
        String filter = "";
        if (version != null) { filter = " AND q.document_version_id=:version"; p.put("version", version); }
        String cursor = "";
        if (after != null) {
            cursor = " WHERE (t.document_version_id, t.id) > (:av, :ai)";
            p.put("av", after.versionId()); p.put("ai", after.id());
        }
        return jdbc.sql("SELECT * FROM (SELECT q.*,v.version_no,v.document_id,"
                + " row_number() OVER (ORDER BY q.document_version_id,q.id) AS number"
                + " FROM planning_question q JOIN document_version v ON v.id=q.document_version_id"
                + " WHERE q.project_id=:project AND q.status<>'SUPERSEDED'" + filter + ") t" + cursor
                + " ORDER BY t.document_version_id,t.id LIMIT :limit").params(p).query().listOfRows();
    }

    public long count(long project, Long version) {
        var p = new HashMap<String, Object>();
        p.put("project", project);
        String filter = "";
        if (version != null) { filter = " AND q.document_version_id=:version"; p.put("version", version); }
        return jdbc.sql("SELECT count(*) FROM planning_question q WHERE q.project_id=:project AND q.status<>'SUPERSEDED'" + filter)
                .params(p).query(Long.class).single();
    }

    public Optional<Map<String, Object>> findForUpdate(long project, long id) {
        return jdbc.sql("""
                SELECT q.*,v.version_no,v.document_id FROM planning_question q LEFT JOIN document_version v ON v.id=q.document_version_id
                 WHERE q.project_id=:project AND q.id=:id FOR UPDATE OF q
                """).param("project", project).param("id", id).query().listOfRows().stream().findFirst();
    }

    public Optional<Map<String, Object>> find(long project, long id) {
        return jdbc.sql("""
                SELECT q.*,v.version_no,v.document_id FROM planning_question q LEFT JOIN document_version v ON v.id=q.document_version_id
                 WHERE q.project_id=:project AND q.id=:id
                """).param("project", project).param("id", id).query().listOfRows().stream().findFirst();
    }

    public void answer(long id, String text, Object payload, long account) {
        jdbc.sql("""
                UPDATE planning_question SET status='ANSWERED',answer=:answer,answer_payload=cast(:payload AS jsonb),answered_by=:account,
                  answered_at=now(),updated_at=now() WHERE id=:id
                """).param("answer", text).param("payload", payload == null ? null : canonical(payload), Types.VARCHAR)
                .param("account", account).param("id", id).update();
    }

    public void dismiss(long id) {
        jdbc.sql("""
                UPDATE planning_question SET status='DISMISSED',answer=null,answer_payload=null,answered_by=null,answered_at=null,updated_at=now()
                 WHERE id=:id
                """).param("id", id).update();
    }

    public boolean hasBaseline(long project) {
        return jdbc.sql("SELECT current_baseline_id IS NOT NULL FROM project WHERE id=:project").param("project", project)
                .query(Boolean.class).optional().orElse(false);
    }

    public boolean projectActive(long project) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM project WHERE id=:project AND archived_at IS NULL)").param("project", project)
                .query(Boolean.class).single();
    }
}
