package com.beez.backend.task.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 검증 테이블을 읽고(현재 계획의 검증 상태) 무효화(STALE)하는 작은 도우미.
 * 검증 엔티티와 실행 로직은 S15P31A108-59 에서 만든다. 여기서는 TaskDetail.validation_status 계산과 무효화만 한다.
 * 무효화는 세 갈래다 — 진행 중 계획 변경(S2, {@link #markStaleForTask}: 끝난 검증만), 작업 취소({@link #invalidateAllForTask}),
 * 담당자 인계(S5, {@link #invalidateForReassign}: DELTA 제외), 설계 발행(S1, {@link #invalidateByIds}: 지목한 검증).
 * 뒤의 셋은 계획 리비전이 그대로라 도는 검증과 대기 job 까지 막는다.
 */
@Component
public class ValidationStatusLookup {

    public static final String NOT_RUN = "NOT_RUN";

    /** 명세 validation_status 값 (DDL validation.status CHECK 와 같음) */
    public static final java.util.Set<String> ALL = java.util.Set.of(
            "NOT_RUN", "RUNNING", "PASS", "NEEDS_INPUT", "NEEDS_REVIEW", "STALE", "ERROR");

    /** 검증 trigger 전부 (DDL validation.trigger CHECK 와 같음). 작업 취소가 무효화하는 범위 */
    private static final List<String> ALL_TRIGGERS = List.of("AGENT", "REVALIDATION", "DELTA");

    /** 담당자 인계가 무효화하는 범위 — DELTA 만 뺀다 ({@link #invalidateForReassign}) */
    private static final List<String> REASSIGN_TRIGGERS = List.of("AGENT", "REVALIDATION");

    private final JdbcClient jdbc;

    public ValidationStatusLookup(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** (작업, 계획 리비전) 의 가장 최근 검증 상태. 없으면 NOT_RUN. "현재 포인터를 중복 저장하지 않는다"(명세 TSK-A03) */
    public String latestStatus(Long taskId, Long planRevisionId) {
        if (planRevisionId == null) {
            return NOT_RUN;
        }
        return jdbc.sql("""
                        select status
                          from validation
                         where task_id = :taskId
                           and plan_revision_id = :revisionId
                         order by id desc
                         limit 1
                        """)
                .param("taskId", taskId)
                .param("revisionId", planRevisionId)
                .query(String.class)
                .optional()
                .orElse(NOT_RUN);
    }

    /**
     * (작업, 계획 리비전) 의 가장 최근 DELTA 검증 번호(상태 무관). 없으면 null.
     * "현재 계획의 가장 최근 DELTA" 는 S4 완료 조건·발행 뒤 재접수·TaskDetail.delta_validation_id 가 모두 같은 기준으로 봐야 해서 여기 한 곳에만 둔다
     */
    public Long latestDeltaValidationId(Long taskId, Long planRevisionId) {
        if (planRevisionId == null) {
            return null;
        }
        return jdbc.sql("""
                        select id from validation
                         where task_id = :taskId and plan_revision_id = :revisionId and trigger = 'DELTA'
                         order by id desc limit 1
                        """)
                .param("taskId", taskId).param("revisionId", planRevisionId)
                .query(Long.class).optional().orElse(null);
    }

    /**
     * 작업이 <b>지금 기다리는</b> DELTA 검증 번호: 현재 계획의 가장 최근 DELTA 가 STALE 이 아닐 때 그 번호, 아니면 null.
     * RESUME 이 DELTA 를 STALE(TASK_RESUMED) 로 바꾸므로, 그 뒤 인계 등으로 다시 WAITING(REVALIDATION) 이 된 작업은 기다리는 DELTA 가 없다.
     * 완료 보고 재전송 판정(TaskStateService)과 TaskDetail.delta_validation_id 가 같은 기준을 쓴다
     */
    public Long pendingDeltaValidationId(Long taskId, Long planRevisionId) {
        Long latest = latestDeltaValidationId(taskId, planRevisionId);
        if (latest == null || "STALE".equals(statusOf(latest))) {
            return null;
        }
        return latest;
    }

    /** {@link #pendingDeltaValidationId} 를 여러 작업에 한 번에 (목록 조회, N+1 방지). key = task_id, 없는 작업은 빠진다 */
    public Map<Long, Long> pendingDeltaValidationIdByTask(List<Long> taskIds) {
        Map<Long, Long> result = new HashMap<>();
        if (taskIds.isEmpty()) {
            return result;
        }
        // 작업마다 가장 최근 DELTA 한 줄을 먼저 고르고(distinct on) 그것이 STALE 이 아닌 것만 남긴다 — "STALE 아닌 것 중 최근" 이 아니다
        List<Map<String, Object>> rows = jdbc.sql("""
                        select task_id, id
                          from (select distinct on (v.task_id) v.task_id, v.id, v.status
                                  from validation v
                                  join task t on t.id = v.task_id and t.current_plan_revision_id = v.plan_revision_id
                                 where v.task_id in (:ids) and v.trigger = 'DELTA'
                                 order by v.task_id, v.id desc) latest
                         where latest.status <> 'STALE'
                        """)
                .param("ids", taskIds)
                .query()
                .listOfRows();
        for (Map<String, Object> row : rows) {
            result.put(((Number) row.get("task_id")).longValue(), ((Number) row.get("id")).longValue());
        }
        return result;
    }

    /** 검증 상태가 PASS 인가 (S4 완료 조건) */
    public boolean isPass(Long validationId) {
        return "PASS".equals(statusOf(validationId));
    }

    /** 검증 한 건의 상태. 없으면 null */
    public String statusOf(Long validationId) {
        return jdbc.sql("select status from validation where id = :v").param("v", validationId).query(String.class)
                .optional().orElse(null);
    }

    /**
     * RESUME (04-task-lifecycle.md §2.3 출구 ①): delta 완료 보고를 거두고 다시 일하는 것이므로, 그 완료 보고를 기다리던 DELTA 검증은
     * 끝났든 돌고 있든 쓸 수 없게 한다. 두지 않으면 다시 일하던 작업이 뒤늦게 도착한 옛 DELTA 의 PASS 로 완료될 수 있다
     * (인계로 다시 WAITING(REVALIDATION) 이 된 뒤 등). 범위는 취소와 같이 대기 job 취소·열린 실행 DISCARDED 까지
     */
    public void invalidateDeltaOnResume(Long taskId) {
        invalidate(taskId, "TASK_RESUMED", List.of("DELTA"));
    }

    /**
     * 현재 계획의 최신 검증 상태가 status 인 작업 번호들 (검증이 없으면 NOT_RUN 으로 본다).
     * 목록 조회에서 페이지를 자르기 전에 필터로 쓴다.
     */
    public List<Long> taskIdsWithStatus(Long projectId, String status) {
        return jdbc.sql("""
                        select t.id
                          from task t
                         where t.project_id = :projectId
                           and coalesce((select v.status
                                           from validation v
                                          where v.task_id = t.id
                                            and v.plan_revision_id = t.current_plan_revision_id
                                          order by v.id desc
                                          limit 1), 'NOT_RUN') = :status
                        """)
                .param("projectId", projectId)
                .param("status", status)
                .query(Long.class)
                .list();
    }

    /** 여러 작업을 한 번에. 목록 조회에서 N+1 을 피한다. key = task_id */
    public Map<Long, String> latestStatusByTask(List<Long> taskIds) {
        Map<Long, String> result = new HashMap<>();
        if (taskIds.isEmpty()) {
            return result;
        }
        // distinct on: Postgres 문법. 작업별로 첫 행(id 가 가장 큰 것)만 남긴다.
        List<Map<String, Object>> rows = jdbc.sql("""
                        select distinct on (v.task_id) v.task_id, v.status
                          from validation v
                          join task t on t.id = v.task_id and t.current_plan_revision_id = v.plan_revision_id
                         where v.task_id in (:ids)
                         order by v.task_id, v.id desc
                        """)
                .param("ids", taskIds)
                .query()
                .listOfRows();
        for (Map<String, Object> row : rows) {
            Long taskId = ((Number) row.get("task_id")).longValue();
            result.put(taskId, (String) row.get("status"));
        }
        return result;
    }

    /**
     * 계획이 바뀌어 이전 검증을 쓸 수 없게 한다. 끝난 것(PASS·NEEDS_*)만 STALE 로. 이미 STALE·ERROR 인 것은 그대로.
     * RUNNING 인 것은 워커가 attempt 로 늦은 결과를 막으므로 여기서 건드리지 않는다 (D6).
     *
     * @return 바뀐 행 수
     */
    public int markStaleForTask(Long taskId, String reason) {
        return jdbc.sql("""
                        update validation
                           set status = 'STALE', stale_reason = :reason, updated_at = now()
                         where task_id = :taskId
                           and status in ('PASS', 'NEEDS_INPUT', 'NEEDS_REVIEW')
                        """)
                .param("taskId", taskId)
                .param("reason", reason)
                .update();
    }

    /** 작업 취소(TSK-A07). 이 작업의 검증을 trigger 와 관계없이 끝난 것·도는 것 모두 쓸 수 없게 한다. */
    public void invalidateAllForTask(Long taskId, String reason) {
        invalidate(taskId, reason, ALL_TRIGGERS);
    }

    /**
     * 담당자 인계(MEM-A09·A10, TSK-A06). 새 담당자가 이전 담당자 쪽에서 요청한 검증으로 이어 가지 못하게, DELTA 를 뺀 검증을 쓸 수 없게 한다.
     * DELTA 는 완료 보고에서 서버가 접수하고 이미 끝난 작업 결과를 보는 검증이라 담당자와 무관하다 — 남겨 두어야
     * delta 완료 보고 뒤 DELTA 검증을 기다리던 작업이 그 검증 PASS 로 그대로 완료(S4)된다 (04-task-lifecycle.md §2.3).
     * REVALIDATION 은 누가 접수하는지(에이전트 요청이 이 값으로 기록되는지)가 검증 접수(-159) 전이라 정해지지 않아 함께 무효화한다.
     */
    public void invalidateForReassign(Long taskId, String reason) {
        invalidate(taskId, reason, REASSIGN_TRIGGERS);
    }

    /**
     * 설계 발행(S1, -163). 번호로 지목한 검증을 trigger 와 관계없이 쓸 수 없게 한다 — 끝난 것·도는 것·대기 중인 것 모두.
     * 범위는 {@link #invalidate} 와 같다 (대기 job 취소, STALE, 열린 실행 DISCARDED). 이미 STALE·ERROR 인 것은 그대로.
     *
     * @return STALE 로 바뀐 행 수
     */
    public int invalidateByIds(List<Long> validationIds, String reason) {
        if (validationIds.isEmpty()) {
            return 0;
        }
        jdbc.sql("""
                        update validation_job
                           set status = 'CANCELLED', lease_token = null, lease_until = null, updated_at = now()
                         where validation_id in (:ids)
                           and status in ('QUEUED', 'LEASED')
                        """)
                .param("ids", validationIds)
                .update();
        int changed = jdbc.sql("""
                        update validation
                           set status = 'STALE', stale_reason = :reason, updated_at = now()
                         where id in (:ids)
                           and status in ('NOT_RUN', 'RUNNING', 'PASS', 'NEEDS_INPUT', 'NEEDS_REVIEW')
                        """)
                .param("ids", validationIds)
                .param("reason", reason)
                .update();
        jdbc.sql("""
                        update validation_run r
                           set status = 'DISCARDED', error = '검증 무효화', finished_at = now(), updated_at = now()
                          from validation v
                         where r.validation_id = v.id
                           and v.id in (:ids)
                           and v.status = 'STALE'
                           and r.status = 'RUNNING'
                        """)
                .param("ids", validationIds)
                .update();
        return changed;
    }

    /**
     * {@link #markStaleForTask} 와 달리 계획 리비전이 그대로인 경우에 쓴다. 지금 도는 검증이 나중에 PASS 로 끝나면 그대로 쓸 수 있게 되므로:
     * - 진행 중인 검증 job 은 CANCELLED
     * - NOT_RUN·RUNNING 도 STALE 로 바꾼다. 워커의 결과 반영(WHERE status='RUNNING')이 0 행이 되어 늦은 결과가 버려진다 (D6 fencing)
     * - 그 검증의 아직 열린 실행(run)은 DISCARDED 로 닫는다. 접수 때 실행을 미리 만들어 두므로(-159), 큐에서 꺼내기 전에
     *   무효화되면 워커가 다시 볼 일이 없어 RUNNING 으로 남는다 (공통 "검증 실행 오류 규약" 마지막 행)
     */
    private void invalidate(Long taskId, String reason, List<String> triggers) {
        jdbc.sql("""
                        update validation_job j
                           set status = 'CANCELLED', lease_token = null, lease_until = null, updated_at = now()
                          from validation v
                         where j.validation_id = v.id
                           and v.task_id = :taskId
                           and v.trigger in (:triggers)
                           and j.status in ('QUEUED', 'LEASED')
                        """)
                .param("taskId", taskId)
                .param("triggers", triggers)
                .update();
        jdbc.sql("""
                        update validation
                           set status = 'STALE', stale_reason = :reason, updated_at = now()
                         where task_id = :taskId
                           and trigger in (:triggers)
                           and status in ('NOT_RUN', 'RUNNING', 'PASS', 'NEEDS_INPUT', 'NEEDS_REVIEW')
                        """)
                .param("taskId", taskId)
                .param("reason", reason)
                .param("triggers", triggers)
                .update();
        jdbc.sql("""
                        update validation_run r
                           set status = 'DISCARDED', error = '검증 무효화', finished_at = now(), updated_at = now()
                          from validation v
                         where r.validation_id = v.id
                           and v.task_id = :taskId
                           and v.trigger in (:triggers)
                           and v.status = 'STALE'
                           and r.status = 'RUNNING'
                        """)
                .param("taskId", taskId)
                .param("triggers", triggers)
                .update();
    }
}
