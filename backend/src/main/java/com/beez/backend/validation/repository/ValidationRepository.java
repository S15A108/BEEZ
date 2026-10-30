package com.beez.backend.validation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * 검증 표(validation·run·job·finding·design_ref·task_ref) 읽기·쓰기 (S15P31A108-159).
 * 엔티티 없이 JdbcClient 로 쓴다. validation 에는 project_id 칸이 없어 모든 조회는 task 와 조인해 프로젝트 조건을 건다.
 */
@Repository
public class ValidationRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ValidationRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- 검증 ----

    public long insertValidation(long taskId, long planRevisionId, long baselineId, String status, String trigger,
                                 String inputHash, String ruleVersion, int currentAttempt, Map<String, Object> summary,
                                 boolean finished) {
        String finishedAt = "null";
        if (finished) {
            finishedAt = "now()";
        }
        return jdbc.sql(("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash,
                                                rule_version, current_attempt, summary, finished_at)
                        values (:t, :r, :b, :s, :tr, :h, :rv, :a, cast(:sum as jsonb), %s)
                        returning id
                        """).formatted(finishedAt))
                .param("t", taskId).param("r", planRevisionId).param("b", baselineId).param("s", status)
                .param("tr", trigger).param("h", inputHash).param("rv", ruleVersion).param("a", currentAttempt)
                .param("sum", toJson(summary))
                .query(Long.class).single();
    }

    private static final String SELECT_VALIDATION = """
            select v.id, t.project_id, v.task_id, v.plan_revision_id, v.baseline_id, v.status, v.trigger, v.stale_reason,
                   v.current_attempt, v.input_snapshot_hash, v.summary::text as summary, v.requested_at, v.finished_at,
                   v.lock_version
              from validation v join task t on t.id = v.task_id
            """;

    /** 이 프로젝트의 검증. 다른 프로젝트 것은 없는 것과 같다 */
    public Optional<ValidationRow> find(long projectId, long validationId) {
        return jdbc.sql(SELECT_VALIDATION + " where v.id = :v and t.project_id = :p")
                .param("v", validationId).param("p", projectId).query(ValidationRepository::validation).optional();
    }

    /**
     * 워커가 반영 직전에 잠그고 읽는다. 작업 취소(ValidationStatusLookup.invalidate)가 job → validation 순서로 갱신하므로
     * 여기서도 job 행을 먼저 잠가 순서를 맞춘다 (교착 방지).
     */
    public Optional<ValidationRow> lock(long validationId) {
        jdbc.sql("select id from validation_job where validation_id = :v for update").param("v", validationId)
                .query(Long.class).optional();
        return jdbc.sql(SELECT_VALIDATION + " where v.id = :v for update of v")
                .param("v", validationId).query(ValidationRepository::validation).optional();
    }

    /** 작업의 검증 이력. id 내림차순, beforeId 보다 작은 것 limit 개 (VAL-A03) */
    public List<ValidationRow> listByTask(long projectId, long taskId, long beforeId, int limit) {
        return jdbc.sql(SELECT_VALIDATION + " where v.task_id = :t and t.project_id = :p and v.id < :before"
                        + " order by v.id desc limit :limit")
                .param("t", taskId).param("p", projectId).param("before", beforeId).param("limit", limit)
                .query(ValidationRepository::validation).list();
    }

    /** 종결: 상태·완료 시각·요약. 워커가 반영할 때 */
    public void finish(long validationId, String status, Map<String, Object> summary) {
        jdbc.sql("""
                        update validation set status = :s, summary = cast(:sum as jsonb), finished_at = now(),
                               lock_version = lock_version + 1, updated_at = now()
                         where id = :v
                        """)
                .param("s", status).param("sum", toJson(summary)).param("v", validationId).update();
    }

    // ---- 참조 ----

    /** 계획 리비전의 설계 참조를 검증 참조로 복사한다 (당시 본 버전 고정). 복사한 줄 수 */
    public int copyDesignRefs(long validationId, long planRevisionId) {
        return jdbc.sql("""
                        insert into validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
                        select :v, design_item_id, design_item_version_id, relation
                          from task_plan_design_ref where plan_revision_id = :r
                        """)
                .param("v", validationId).param("r", planRevisionId).update();
    }

    public void insertTaskRef(long validationId, long relatedTaskId, long relatedPlanRevisionId) {
        jdbc.sql("""
                        insert into validation_task_ref (validation_id, related_task_id, related_plan_revision_id)
                        values (:v, :t, :r)
                        """)
                .param("v", validationId).param("t", relatedTaskId).param("r", relatedPlanRevisionId).update();
    }

    public List<Map<String, Object>> designRefs(long validationId) {
        return jdbc.sql("""
                        select design_item_id, design_item_version_id, relation from validation_design_ref
                         where validation_id = :v order by design_item_id
                        """)
                .param("v", validationId).query().listOfRows();
    }

    public List<Map<String, Object>> taskRefs(long validationId) {
        return jdbc.sql("""
                        select related_task_id, related_plan_revision_id from validation_task_ref
                         where validation_id = :v order by related_task_id
                        """)
                .param("v", validationId).query().listOfRows();
    }

    /**
     * 같은 설계 항목을 현재 계획에서 참조하는 열린 다른 작업 (READY·IN_PROGRESS·WAITING). 관련 계획으로 대조한다.
     * 행: task_id, plan_revision_id, status, design_item_id, relation — 작업·항목 쌍마다 한 줄
     */
    public List<Map<String, Object>> openTasksSharingItems(long projectId, long taskId, long planRevisionId) {
        return jdbc.sql("""
                        select t.id as task_id, t.current_plan_revision_id as plan_revision_id, t.status,
                               r.design_item_id, r.relation
                          from task t
                          join task_plan_design_ref r on r.plan_revision_id = t.current_plan_revision_id
                         where t.project_id = :p and t.id <> :t
                           and t.status in ('READY', 'IN_PROGRESS', 'WAITING')
                           and r.design_item_id in (select design_item_id from task_plan_design_ref where plan_revision_id = :r)
                         order by t.id, r.design_item_id
                        """)
                .param("p", projectId).param("t", taskId).param("r", planRevisionId).query().listOfRows();
    }

    // ---- 발견 항목 ----

    public long insertFinding(long validationId, FindingRow f) {
        return jdbc.sql("""
                        insert into validation_finding (validation_id, validation_run_id, source, kind, severity, design_item_id,
                                                        design_item_version_id, related_task_id, message, sources, resolution,
                                                        next_action, check_key, evidence_ids)
                        values (:v, :run, :src, :k, :sev, :item, :ver, :task, :msg, cast(:sources as jsonb), :res, :next,
                                :ck, cast(:ev as jsonb))
                        returning id
                        """)
                .param("v", validationId).param("run", f.validationRunId(), Types.BIGINT).param("src", f.source())
                .param("k", f.kind()).param("sev", f.severity()).param("item", f.designItemId(), Types.BIGINT)
                .param("ver", f.designItemVersionId(), Types.BIGINT).param("task", f.relatedTaskId(), Types.BIGINT)
                .param("msg", f.message()).param("sources", toJson(f.sources())).param("res", f.resolution())
                .param("next", f.nextAction(), Types.VARCHAR).param("ck", f.checkKey(), Types.VARCHAR)
                .param("ev", toJson(f.evidenceIds()))
                .query(Long.class).single();
    }

    public List<FindingRow> findings(long validationId) {
        return jdbc.sql("""
                        select id, validation_id, validation_run_id, source, kind, severity, design_item_id, design_item_version_id,
                               related_task_id, message, sources::text as sources, resolution, next_action, check_key,
                               evidence_ids::text as evidence_ids
                          from validation_finding where validation_id = :v order by id
                        """)
                .param("v", validationId).query(this::finding).list();
    }

    // ---- 실행(run) ----

    public long insertRun(long validationId, int attempt, UUID leaseToken, String status) {
        return jdbc.sql("""
                        insert into validation_run (validation_id, attempt, lease_token, kind, status)
                        values (:v, :a, :lease, 'MODEL', :s) returning id
                        """)
                .param("v", validationId).param("a", attempt).param("lease", leaseToken, Types.OTHER).param("s", status)
                .query(Long.class).single();
    }

    /** 현재 시도의 실행. 없으면 빈 값 */
    public Optional<Map<String, Object>> run(long validationId, int attempt) {
        return jdbc.sql("""
                        select id, status, lease_token, error, analyzer_version from validation_run
                         where validation_id = :v and attempt = :a
                        """)
                .param("v", validationId).param("a", attempt).query().listOfRows().stream().findFirst();
    }

    /** 실행 종결. 이미 종결된 실행은 덮어쓰지 않는다 (RUNNING 일 때만). 바뀐 줄 수 */
    public int finishRun(long runId, String status, String error, String analyzerVersion, long durationMs) {
        return jdbc.sql("""
                        update validation_run
                           set status = :s, error = :e, analyzer_version = :m, finished_at = now(), duration_ms = :d, updated_at = now()
                         where id = :id and status = 'RUNNING'
                        """)
                .param("s", status).param("e", error, Types.VARCHAR).param("m", analyzerVersion, Types.VARCHAR)
                .param("d", (int) durationMs).param("id", runId).update();
    }

    // ---- 작업 큐(job) ----

    public void insertJob(long validationId) {
        jdbc.sql("insert into validation_job (validation_id, status) values (:v, 'QUEUED')").param("v", validationId).update();
    }

    /**
     * 대기 중인 작업 하나를 잡는다. FOR UPDATE SKIP LOCKED: 다른 워커가 잡은 줄은 건너뛴다.
     * 잡으면 LEASED 로 바꾸고 리스 토큰·만료 시각을 남긴다. 토큰은 현재 시도의 실행(run)에도 같이 적어 결과 반영 때 대조한다.
     */
    public Optional<JobLease> lease(int leaseSeconds) {
        UUID token = UUID.randomUUID();
        return jdbc.sql("""
                        with picked as (
                          select j.id, j.validation_id from validation_job j
                           where j.status = 'QUEUED' and j.next_run_at <= now()
                             and exists (select 1 from validation v join validation_run r
                                           on r.validation_id = v.id and r.attempt = v.current_attempt and r.status = 'RUNNING'
                                          where v.id = j.validation_id and v.status = 'RUNNING')
                           order by j.next_run_at limit 1
                             for update skip locked
                        ), leased as (
                          update validation_job j set status = 'LEASED', lease_token = :tok, attempts = j.attempts + 1,
                                 lease_until = now() + make_interval(secs => :secs), updated_at = now()
                            from picked where j.id = picked.id
                          returning j.id, j.validation_id
                        ), run_marked as (
                          update validation_run r set lease_token = :tok, updated_at = now()
                            from leased, validation v
                           where v.id = leased.validation_id and r.validation_id = v.id and r.attempt = v.current_attempt
                          returning r.attempt, r.validation_id
                        )
                        select leased.id as job_id, leased.validation_id, run_marked.attempt
                          from leased join run_marked on run_marked.validation_id = leased.validation_id
                        """)
                .param("tok", token, Types.OTHER).param("secs", leaseSeconds)
                .query((rs, n) -> new JobLease(rs.getLong("job_id"), rs.getLong("validation_id"), rs.getInt("attempt"), token))
                .optional();
    }

    /** 작업 종결. DONE·FAILED 는 리스 정보를 비운다 (V2 CHECK: LEASED 일 때만 리스가 있다) */
    public void finishJob(long jobId, String status) {
        jdbc.sql("""
                        update validation_job set status = :s, lease_token = null, lease_until = null, updated_at = now()
                         where id = :id
                        """)
                .param("s", status).param("id", jobId).update();
    }

    // ---- 행 변환 ----

    private static ValidationRow validation(ResultSet rs, int n) throws SQLException {
        return new ValidationRow(rs.getLong("id"), rs.getLong("project_id"), rs.getLong("task_id"),
                rs.getLong("plan_revision_id"), rs.getLong("baseline_id"), rs.getString("status"), rs.getString("trigger"),
                rs.getString("stale_reason"), rs.getInt("current_attempt"), rs.getString("input_snapshot_hash"),
                rs.getString("summary"), instant(rs, "requested_at"),
                instant(rs, "finished_at"), rs.getInt("lock_version"));
    }

    @SuppressWarnings("unchecked")
    private FindingRow finding(ResultSet rs, int n) throws SQLException {
        // readValue: JSON 문자열을 List 로 바꾼다
        List<Map<String, Object>> sources = json.readValue(rs.getString("sources"), List.class);
        List<String> evidence = json.readValue(rs.getString("evidence_ids"), List.class);
        return new FindingRow(rs.getLong("id"), rs.getLong("validation_id"), nullableLong(rs, "validation_run_id"),
                rs.getString("source"), rs.getString("kind"), rs.getString("severity"), nullableLong(rs, "design_item_id"),
                nullableLong(rs, "design_item_version_id"), nullableLong(rs, "related_task_id"), rs.getString("message"),
                sources, rs.getString("resolution"), rs.getString("next_action"), rs.getString("check_key"), evidence);
    }

    private String toJson(Object value) {
        // writeValueAsString: 객체를 JSON 문자열로
        return json.writeValueAsString(value);
    }

    static Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        if (value == null) {
            return null;
        }
        return value.toInstant();
    }
}
