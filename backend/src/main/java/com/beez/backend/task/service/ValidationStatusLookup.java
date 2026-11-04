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

    /** 같은 값을 명세 순서대로 (현황 집계가 내려 주는 순서, MON-V01) */
    public static final List<String> ORDERED = List.of(
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

    /** (작업, 계획 리비전) 의 가장 최근 검증 번호. {@link #latestStatus} 와 같은 기준. 없으면 null (TaskDetail.validation) */
    public Long latestValidationId(Long taskId, Long planRevisionId) {
        if (planRevisionId == null) {
            return null;
        }
        return jdbc.sql("""
                        select id
                          from validation
                         where task_id = :taskId
                           and plan_revision_id = :revisionId
                         order by id desc
                         limit 1
                        """)
                .param("taskId", taskId)
                .param("revisionId", planRevisionId)
                .query(Long.class)
                .optional()
                .orElse(null);
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

    /**
     * 현재 계획의 가장 최근 DELTA 가 <b>아직 결과가 안 난</b>(NOT_RUN·RUNNING) 것이면 그 번호, 아니면 null.
     * 계획 변경을 막는 기준이다(TaskPlanService) — 결과가 나면 PASS 는 S4 로 완료되고, NEEDS_INPUT·NEEDS_REVIEW·ERROR 는 계획을 고쳐
     * 나가는 길(§2.3 출구 ②)이 열려 있어야 한다. {@link #pendingDeltaValidationId} 는 끝난 DELTA 도 "기다리는 보고" 로 보므로 여기에 쓰지 않는다
     */
    public Long undecidedDeltaValidationId(Long taskId, Long planRevisionId) {
        Long latest = latestDeltaValidationId(taskId, planRevisionId);
        if (latest == null) {
            return null;
        }
        String status = statusOf(latest);
        if ("NOT_RUN".equals(status) || "RUNNING".equals(status)) {
            return latest;
        }
        return null;
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

    /** 실제 검색·원문을 사용해 끝난 검증은 검색에 쓰인 제목·접근 방식·패킷이 바뀌면 이어 쓸 수 없다. */
    public boolean hasSearchBackedValidation(Long taskId, Long revisionId) {
        return jdbc.sql("""
                        select exists (select 1 from validation
                                        where task_id = :taskId and plan_revision_id = :revisionId
                                          and trigger <> 'DELTA'
                                          and status in ('PASS', 'NEEDS_INPUT', 'NEEDS_REVIEW')
                                          and summary ->> 'analysis_baseline_id' is not null)
                        """)
                .param("taskId", taskId).param("revisionId", revisionId).query(Boolean.class).single();
    }

    /**
     * RESUME (04-task-lifecycle.md §2.3 출구 ①): delta 완료 보고를 거두고 다시 일하는 것이므로, 그 완료 보고를 기다리던 DELTA 검증은
     * 끝났든 돌고 있든 쓸 수 없게 한다. 두지 않으면 다시 일하던 작업이 뒤늦게 도착한 옛 DELTA 의 PASS 로 완료될 수 있다
     * (인계로 다시 WAITING(REVALIDATION) 이 된 뒤 등). 범위는 취소와 같이 대기 job 취소·열린 실행 DISCARDED 까지
     */
    public void invalidateDeltaOnResume(Long taskId) {
        invalidate(taskId, "TASK_RESUMED", List.of("DELTA"));
        // ERROR 로 끝난 DELTA 도 "그 완료 대기는 끝났다" 는 뜻으로 STALE 로 바꾼다 (일반 무효화는 ERROR 를 두지만, 여기서는 두면
        // 그 뒤 인계로 다시 WAITING 이 된 작업이 옛 ERROR DELTA 를 기다리는 것으로 보인다). 오류 이력은 실행·발견 항목에 그대로 남는다
        jdbc.sql("""
                        update validation
                           set status = 'STALE', stale_reason = 'TASK_RESUMED', updated_at = now()
                         where task_id = :taskId and trigger = 'DELTA' and status = 'ERROR'
                        """)
                .param("taskId", taskId)
                .update();
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

    /**
     * 현재 계획의 최신 검증 상태별 작업 수 (MON-V01). 작업마다 한 번씩, 검증이 없으면 NOT_RUN. 계획이 없는(DRAFT) 작업은 세지 않는다.
     * {@link #taskIdsWithStatus} 와 같은 기준. key = status
     */
    public Map<String, Integer> countTasksByLatestStatus(Long projectId) {
        Map<String, Integer> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbc.sql("""
                        select coalesce((select v.status
                                           from validation v
                                          where v.task_id = t.id
                                            and v.plan_revision_id = t.current_plan_revision_id
                                          order by v.id desc
                                          limit 1), 'NOT_RUN') as status, count(*) as cnt
                          from task t
                         where t.project_id = :projectId
                           and t.current_plan_revision_id is not null
                         group by 1
                        """)
                .param("projectId", projectId)
                .query()
                .listOfRows();
        for (Map<String, Object> row : rows) {
            result.put((String) row.get("status"), ((Number) row.get("cnt")).intValue());
        }
        return result;
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
     * 검증 입력이 같은 새 계획 리비전으로 이전 계획의 검증을 이어 받는다 (AC-13, S15P31A108-162. 04-task-lifecycle.md §2.2 S2).
     * 검증은 계획 리비전에 묶여 있어(G1·TaskDetail·현황 집계가 모두 "현재 리비전의 가장 최근 검증" 을 본다) 그냥 두면 제목만 바꿔도
     * 검증이 없는 계획이 된다. 그래서 이전 리비전의 <b>DELTA 를 뺀 가장 최근 검증 한 건</b>이 끝난 것(PASS·NEEDS_INPUT·NEEDS_REVIEW)이면
     * 같은 내용(상태·종류·입력 해시·규칙 버전·요약·참조·관련 계획·발견 항목)으로 새 리비전에 한 줄 더 넣는다.
     * DELTA 를 빼는 이유: RESUME 으로 거둔 완료 보고의 DELTA(STALE) 가 가장 최근이어도 그 전의 PASS 로 일하던 작업이다.
     * <ul>
     *   <li>summary 에 {@code carried_from}(원본 = 처음 실제로 돌린 검증 번호. 복사본의 복사본도 같은 원본)과 {@code carried_from_revision}
     *       을 남긴다. 실행(validation_run)은 복사하지 않는다 — 실제로 돈 것은 원본이고, 가짜 분석기 판정(G1)과 사용량 집계는 원본 번호로 본다</li>
     *   <li>가장 최근 것이 RUNNING 이면 여기서는 이어 받지 않는다. 그 결과가 반영될 때 입력 비교(해시)를 통과하면 결과 반영
     *       (AnalysisResultApplier)이 {@link #carryForward} 로 이어 받는다. STALE·ERROR 는 이어 받을 것이 없다</li>
     *   <li>DELTA 는 이어 받지 않는다 — delta 를 기다리는 동안은 계획 변경이 거부되고(TaskPlanService), 거둔 DELTA 는 STALE 이다</li>
     * </ul>
     *
     * @return 새로 넣은 검증 번호. 이어 받은 것이 없으면 null
     */
    public Long carryForwardLatest(Long taskId, Long fromRevisionId, Long toRevisionId) {
        List<Map<String, Object>> latest = jdbc.sql("""
                        select id, status
                          from validation
                         where task_id = :taskId and plan_revision_id = :from and trigger <> 'DELTA'
                         order by id desc
                         limit 1
                        """)
                .param("taskId", taskId).param("from", fromRevisionId)
                .query().listOfRows();
        if (latest.isEmpty()) {
            return null;
        }
        String status = (String) latest.get(0).get("status");
        boolean finished = "PASS".equals(status) || "NEEDS_INPUT".equals(status) || "NEEDS_REVIEW".equals(status);
        if (!finished) {
            return null;
        }
        return carryForward(((Number) latest.get(0).get("id")).longValue(), toRevisionId);
    }

    /**
     * 검증 한 건을 다른 계획 리비전으로 이어 받는다 ({@link #carryForwardLatest} 의 복사 부분). 도는 사이 계획이 입력 같은 리비전으로
     * 바뀐 검증이 끝났을 때 결과 반영(AnalysisResultApplier)이 끝난 그 검증으로 직접 부른다. 호출자가 끝난 검증(PASS·NEEDS_*)만 넘긴다.
     *
     * @return 새로 넣은 검증 번호
     */
    public Long carryForward(long source, Long toRevisionId) {
        boolean samePolicyInputs = jdbc.sql("""
                select case when v.summary ->> 'policy_analysis_version' = 'policy-gap-v1'
                            then old_plan.title is not distinct from new_plan.title
                             and old_plan.approach is not distinct from new_plan.approach
                            else true end
                  from validation v
                  join task_plan_revision old_plan on old_plan.id=v.plan_revision_id
                  join task_plan_revision new_plan on new_plan.id=:to and new_plan.task_id=v.task_id
                 where v.id=:source
                """).param("to", toRevisionId).param("source", source).query(Boolean.class).optional().orElse(false);
        if (!samePolicyInputs) return null;
        // 원본 번호는 원본의 carried_from 이 있으면 그것(복사본의 복사본), 없으면 원본 자신
        Long copy = jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash,
                                                rule_version, current_attempt, summary, finished_at)
                        select task_id, :to, baseline_id, status, trigger, input_snapshot_hash, rule_version, current_attempt,
                               coalesce(summary, '{}'::jsonb)
                                   || jsonb_build_object('carried_from', coalesce((summary ->> 'carried_from')::bigint, id),
                                                         'carried_from_revision', plan_revision_id),
                               finished_at
                          from validation
                         where id = :source
                        returning id
                        """)
                .param("to", toRevisionId).param("source", source)
                .query(Long.class).single();
        jdbc.sql("""
                        insert into validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
                        select :copy, design_item_id, design_item_version_id, relation
                          from validation_design_ref where validation_id = :source
                        """)
                .param("copy", copy).param("source", source).update();
        jdbc.sql("""
                        insert into validation_policy_ref (validation_id, design_item_id, design_item_version_id)
                        select :copy, design_item_id, design_item_version_id
                          from validation_policy_ref where validation_id = :source
                        """)
                .param("copy", copy).param("source", source).update();
        // 검색 후보는 이력용으로만 복사하며 발행 무효화와 시작 판정에는 쓰지 않는다.
        jdbc.sql("""
                        insert into validation_candidate_ref (validation_id, design_item_id, design_item_version_id)
                        select :copy, design_item_id, design_item_version_id
                          from validation_candidate_ref where validation_id = :source
                        """)
                .param("copy", copy).param("source", source).update();
        jdbc.sql("""
                        insert into validation_task_ref (validation_id, related_task_id, related_plan_revision_id)
                        select :copy, related_task_id, related_plan_revision_id
                          from validation_task_ref where validation_id = :source
                        """)
                .param("copy", copy).param("source", source).update();
        // 발견 항목은 원본 실행(validation_run_id)을 그대로 가리킨다 — 근거가 된 실행은 원본 것이다
        jdbc.sql("""
                        insert into validation_finding (validation_id, validation_run_id, source, kind, severity, design_item_id,
                                                        design_item_version_id, related_task_id, message, sources, resolution,
                                                        next_action, check_key, evidence_ids, policy_need_id, conflicting_design_refs)
                        select :copy, validation_run_id, source, kind, severity, design_item_id, design_item_version_id,
                               related_task_id, message, sources, resolution, next_action, check_key, evidence_ids,
                               policy_need_id, conflicting_design_refs
                          from validation_finding where validation_id = :source
                         order by id
                        """)
                .param("copy", copy).param("source", source).update();
        return copy;
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
