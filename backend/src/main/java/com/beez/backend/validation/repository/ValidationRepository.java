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

    /** 검증이 속한 프로젝트. 잠그지 않는 읽기 — 워커가 프로젝트 행을 잠그기 전에 어느 프로젝트인지 알아내는 데 쓴다 */
    public Optional<Long> projectIdOf(long validationId) {
        return jdbc.sql("select t.project_id from validation v join task t on t.id = v.task_id where v.id = :v")
                .param("v", validationId).query(Long.class).optional();
    }

    /**
     * 워커가 반영 직전에 잠그고 읽는다. 프로젝트 행은 호출자가 먼저 잠근다 (계획 변경·상태 보고와 같은 project → task 순서). 작업 취소(ValidationStatusLookup.invalidate)가 job → validation 순서로 갱신하므로
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

    /**
     * 무효화 (결과를 받는 사이 계획이 바뀐 경우). 작업 쪽 무효화(ValidationStatusLookup)와 같이 상태·사유만 바꾼다.
     * RUNNING 일 때만 바꾼다 — 이미 다른 경로가 STALE·ERROR 로 바꿨으면 덮어쓰지 않는다
     */
    public void markStale(long validationId, String reason) {
        markStale(validationId, reason, Map.of());
    }

    /**
     * 무효화 + 요약에 남길 값(모델을 이미 부른 뒤 버리는 결과의 모델·사용량 등). 판정은 버려도 실제 호출의 사용량은
     * 비용 집계(-168)에 쓰도록 summary 에 더한다. 비어 있으면 요약은 그대로
     */
    public void markStale(long validationId, String reason, Map<String, Object> summaryAdditions) {
        jdbc.sql("""
                        update validation set status = 'STALE', stale_reason = :reason,
                               summary = coalesce(summary, '{}'::jsonb) || cast(:add as jsonb), updated_at = now()
                         where id = :v and status = 'RUNNING'
                        """)
                .param("reason", reason).param("add", toJson(summaryAdditions)).param("v", validationId).update();
    }

    /** 작업의 현재 계획 리비전 */
    public Optional<Long> currentPlanRevision(long taskId) {
        return jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).optional();
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

    /**
     * 승인 항목 버전의 원문 출처 (명세 Source: document_id·document_version_id·version_no·location). 출처가 없는 버전은 행이 없다.
     * 행: id(항목 버전), document_id, document_version_id, version_no, locator(원문 위치 JSON 문자열, 없으면 null).
     * 표시용 location 은 워커가 locator 에서 만든다
     */
    public List<Map<String, Object>> designSources(List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select v.id, dv.document_id, v.source_document_version_id as document_version_id, dv.version_no,
                               v.source_locator::text as locator
                          from design_item_version v
                          join document_version dv on dv.id = v.source_document_version_id
                         where v.id in (:v)
                        """)
                .param("v", versionIds).query().listOfRows();
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

    /** 실행 종결 (모델을 부르지 않은 경우). 이미 종결된 실행은 덮어쓰지 않는다 (RUNNING 일 때만). 바뀐 줄 수 */
    public int finishRun(long runId, String status, String error, String analyzerVersion, long durationMs) {
        return finishRun(runId, status, error, analyzerVersion, durationMs, null);
    }

    /**
     * 실행 종결. 모델을 불렀으면 사용량(usage 배열)을 함께 남긴다 — 판정을 버리는 DISCARDED·만료·오류도 마찬가지.
     * 비용 집계(-168)는 validation.summary 가 아니라 여기서 시도별로 읽는다
     */
    public int finishRun(long runId, String status, String error, String analyzerVersion, long durationMs, Object usage) {
        String usageJson = null;
        if (usage != null) {
            usageJson = toJson(usage);
        }
        return jdbc.sql("""
                        update validation_run
                           set status = :s, error = :e, analyzer_version = :m, finished_at = now(), duration_ms = :d,
                               usage = cast(:u as jsonb), updated_at = now()
                         where id = :id and status = 'RUNNING'
                        """)
                .param("s", status).param("e", error, Types.VARCHAR).param("m", analyzerVersion, Types.VARCHAR)
                .param("d", (int) durationMs).param("u", usageJson, Types.VARCHAR).param("id", runId).update();
    }

    /**
     * 이미 종결된 실행에 늦게 도착한 사용량을 남긴다 (리스 회수로 ERROR 가 된 뒤 예전 워커의 응답이 온 경우).
     * 판정은 버리지만 실제 호출 비용은 그 실행에 보존한다. 사용량이 이미 있으면 덮어쓰지 않는다
     */
    public int recordLateUsage(long runId, Object usage) {
        return jdbc.sql("""
                        update validation_run set usage = cast(:u as jsonb), updated_at = now()
                         where id = :id and status <> 'RUNNING' and usage is null
                        """)
                .param("u", toJson(usage), Types.VARCHAR).param("id", runId).update();
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

    /**
     * 작업의 리스 상태. 행: status, lease_token, expired(리스 유효기간이 지났는가 — 지금 시각 clock_timestamp 기준.
     * now() 는 트랜잭션 시작 시각이라 쓰지 않는다)
     */
    public Optional<Map<String, Object>> jobLease(long jobId) {
        return jdbc.sql("""
                        select status, lease_token,
                               (lease_until is not null and lease_until <= clock_timestamp()) as expired
                          from validation_job where id = :id
                        """)
                .param("id", jobId).query().listOfRows().stream().findFirst();
    }

    /**
     * 늦은 결과를 버릴 때, 이 리스로 잡은 작업이 아직 LEASED 로 남아 있으면 CANCELLED 로 닫는다.
     * 리스 토큰이 같을 때만 — 그 사이 다른 워커가 다시 잡았으면 건드리지 않는다
     */
    public void cancelJobIfStillLeased(long jobId, UUID leaseToken) {
        jdbc.sql("""
                        update validation_job set status = 'CANCELLED', lease_token = null, lease_until = null, updated_at = now()
                         where id = :id and status = 'LEASED' and lease_token = :tok
                        """)
                .param("id", jobId).param("tok", leaseToken, Types.OTHER).update();
    }

    /**
     * 리스가 만료된 작업 하나 (워커가 AI 호출 중 죽었거나 재배포로 내려간 경우). 그 리스로 표시한 실행의 시도 번호를 함께 돌려준다.
     * 리스 시간(lease-seconds)은 AI 읽기 시간 초과보다 길어, 살아 있는 워커라면 그 전에 결과를 반영한다.
     * FOR UPDATE SKIP LOCKED: 다른 워커가 같은 작업을 회수 중이면 건너뛴다. 회수 자체는 반영 단계({@code applyLeaseExpired})가
     * 다시 잠그고 확인하므로, 두 워커가 같은 작업을 집어도 한 번만 종결된다.
     */
    public Optional<JobLease> expiredLease() {
        return jdbc.sql("""
                        select j.id as job_id, j.validation_id, r.attempt, j.lease_token
                          from validation_job j
                          join validation_run r on r.validation_id = j.validation_id and r.lease_token = j.lease_token
                         where j.status = 'LEASED' and j.lease_until < now()
                         order by j.lease_until
                         limit 1
                           for update of j skip locked
                        """)
                .query((rs, n) -> new JobLease(rs.getLong("job_id"), rs.getLong("validation_id"), rs.getInt("attempt"),
                        rs.getObject("lease_token", UUID.class)))
                .optional();
    }

    /** 작업 종결. DONE·FAILED·CANCELLED 는 리스 정보를 비운다 (V2 CHECK: LEASED 일 때만 리스가 있다) */
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
