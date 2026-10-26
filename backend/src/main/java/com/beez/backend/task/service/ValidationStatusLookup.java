package com.beez.backend.task.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 검증 테이블을 읽고(현재 계획의 검증 상태) 무효화(STALE)하는 작은 도우미.
 * 검증 엔티티와 실행 로직은 S15P31A108-59 에서 만든다. 여기서는 TaskDetail.validation_status 계산과 계획 변경 시 STALE 처리만 한다.
 */
@Component
public class ValidationStatusLookup {

    public static final String NOT_RUN = "NOT_RUN";

    /** 명세 validation_status 값 (DDL validation.status CHECK 와 같음) */
    public static final java.util.Set<String> ALL = java.util.Set.of(
            "NOT_RUN", "RUNNING", "PASS", "NEEDS_INPUT", "NEEDS_REVIEW", "STALE", "ERROR");

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
}
