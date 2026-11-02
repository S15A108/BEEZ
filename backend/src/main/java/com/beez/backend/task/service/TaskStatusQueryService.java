package com.beez.backend.task.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskStatusResponses.AgentWork;
import com.beez.backend.task.dto.TaskStatusResponses.StateCount;
import com.beez.backend.task.dto.TaskStatusResponses.TaskSummary;
import com.beez.backend.task.dto.TaskStatusResponses.ValidationSummary;
import com.beez.backend.task.dto.TaskStatusResponses.WaitingCount;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업·검증·에이전트 현황 조회 (S15P31A108-64, MON-03·04·07). 읽기만 한다.
 * <ul>
 *   <li>MON-T01 작업 상태 현황 — 작업 상태별·대기 이유별 건수. 검증 상태와 섞지 않는다. 모든 값이 0 이어도 한 줄씩 준다</li>
 *   <li>MON-V01 검증 상태 현황 — 작업마다 <b>현재 계획의 가장 최근 검증</b> 상태 하나로 센다 (TaskDetail.validation_status 와 같은 기준,
 *       검증이 없으면 NOT_RUN). 과거 시도 수가 아니다. 계획이 아직 없는(DRAFT) 작업은 세지 않는다</li>
 *   <li>MON-A05 에이전트 현재 작업 현황 — 에이전트 세션이 <b>명시 보고한</b> 현재 작업(agent_session.current_task_id)과 그 작업의 상태·
 *       대기 사유·검증 상태. 보고가 없으면 실행 중·완료를 추정하지 않는다(작업 칸이 빈 채로 세션만 나온다). 폐기된 토큰의 세션은 뺀다.
 *       연결 여부 자체는 AGT-A05 가 준다</li>
 * </ul>
 * 작업 관계(MON-06)는 관계 조회 API(MON-A03, -167)가 이미 제공한다.
 */
@Service
public class TaskStatusQueryService {

    private final ProjectAccessChecker access;
    private final JdbcClient jdbc;

    public TaskStatusQueryService(ProjectAccessChecker access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public TaskSummary taskSummary(CallerContext caller, Long projectId) {
        access.requireMember(caller, projectId);
        Map<String, Integer> byStatus = counts("""
                select status as key, count(*) as cnt from task where project_id = :p group by status
                """, projectId);
        Map<String, Integer> byReason = counts("""
                select waiting_reason as key, count(*) as cnt from task
                 where project_id = :p and status = 'WAITING' and waiting_reason is not null
                 group by waiting_reason
                """, projectId);
        List<StateCount> statuses = new ArrayList<>();
        for (TaskStatus status : TaskStatus.values()) {
            statuses.add(new StateCount(status.name(), byStatus.getOrDefault(status.name(), 0)));
        }
        List<WaitingCount> reasons = new ArrayList<>();
        for (WaitingReason reason : WaitingReason.values()) {
            reasons.add(new WaitingCount(reason.name(), byReason.getOrDefault(reason.name(), 0)));
        }
        return new TaskSummary(statuses, reasons);
    }

    @Transactional(readOnly = true)
    public ValidationSummary validationSummary(CallerContext caller, Long projectId) {
        access.requireMember(caller, projectId);
        // 작업마다 현재 계획의 가장 최근 검증 한 건 (lateral: 작업 행마다 그 안쪽 조회를 한 번씩 돈다). 없으면 NOT_RUN
        Map<String, Integer> byStatus = counts("""
                select coalesce(v.status, 'NOT_RUN') as key, count(*) as cnt
                  from task t
                  left join lateral (
                      select status from validation
                       where task_id = t.id and plan_revision_id = t.current_plan_revision_id
                       order by id desc limit 1
                  ) v on true
                 where t.project_id = :p and t.current_plan_revision_id is not null
                 group by coalesce(v.status, 'NOT_RUN')
                """, projectId);
        List<StateCount> statuses = new ArrayList<>();
        for (String status : ValidationStatusLookup.ORDERED) {
            statuses.add(new StateCount(status, byStatus.getOrDefault(status, 0)));
        }
        return new ValidationSummary(statuses);
    }

    /**
     * @param memberId 멤버 필터 (project_member.id). 없으면 전체
     * @param cursor   이전 응답의 next_cursor (세션 번호, 내림차순)
     */
    @Transactional(readOnly = true)
    public PageResponse<AgentWork> agentWorkStatus(CallerContext caller, Long projectId, Long memberId, String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        int size = PageResponse.normalizeLimit(limit);
        long beforeId = PageResponse.beforeId(cursor);
        // 세션 → 토큰(프로젝트·계정) → 멤버. 보고된 작업이 있으면 작업·현재 계획의 가장 최근 검증 상태를 같이 읽는다
        List<Map<String, Object>> rows = jdbc.sql("""
                        select s.id as session_id, m.id as member_id, k.name as agent_name,
                               t.id as task_id, t.title as task_title, t.status as task_status, t.waiting_reason,
                               t.current_plan_revision_id,
                               (select v.status from validation v
                                 where v.task_id = t.id and v.plan_revision_id = t.current_plan_revision_id
                                 order by v.id desc limit 1) as validation_status
                          from agent_session s
                          join agent_token k on k.id = s.agent_token_id and k.project_id = :p and k.revoked_at is null
                          join project_member m on m.project_id = k.project_id and m.account_id = k.account_id
                          left join task t on t.id = s.current_task_id and t.project_id = :p
                         where s.id < :before
                           and (:member is null or m.id = :member)
                         order by s.id desc
                         limit :limit
                        """)
                .param("p", projectId).param("before", beforeId).param("member", memberId, java.sql.Types.BIGINT)
                .param("limit", size + 1)
                .query().listOfRows();
        return PageResponse.fromRows(rows, size, row -> ((Number) row.get("session_id")).longValue(), page -> {
            List<AgentWork> items = new ArrayList<>();
            for (Map<String, Object> row : page) {
                String taskStatus = null;
                String validationStatus = null;
                if (row.get("task_id") != null) {
                    taskStatus = (String) row.get("task_status");
                    validationStatus = (String) row.get("validation_status");
                    if (validationStatus == null && row.get("current_plan_revision_id") != null) {
                        validationStatus = ValidationStatusLookup.NOT_RUN;
                    }
                }
                items.add(new AgentWork(Ids.str(((Number) row.get("member_id")).longValue()),
                        Ids.str(((Number) row.get("session_id")).longValue()), (String) row.get("agent_name"),
                        Ids.str(nullableLong(row.get("task_id"))), (String) row.get("task_title"), taskStatus, validationStatus,
                        (String) row.get("waiting_reason"), null));
            }
            return items;
        });
    }

    private Map<String, Integer> counts(String sql, Long projectId) {
        Map<String, Integer> out = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql(sql).param("p", projectId).query().listOfRows()) {
            out.put((String) row.get("key"), ((Number) row.get("cnt")).intValue());
        }
        return out;
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }
}
