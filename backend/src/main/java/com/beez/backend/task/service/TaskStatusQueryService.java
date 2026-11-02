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
import com.beez.backend.task.repository.TaskStatusRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업·검증·에이전트 현황 조회 (S15P31A108-64, MON-03·04·07). 읽기만 한다.
 * <ul>
 *   <li>MON-T01 작업 상태 현황 — 작업 상태별·대기 이유별 건수. 검증 상태와 섞지 않는다. 모든 값이 0 이어도 한 줄씩 준다</li>
 *   <li>MON-V01 검증 상태 현황 — 작업마다 <b>현재 계획의 가장 최근 검증</b> 상태 하나로 센다 (TaskDetail.validation_status 와 같은 기준,
 *       검증이 없으면 NOT_RUN). 과거 시도 수가 아니다. 계획이 아직 없는(DRAFT) 작업은 세지 않는다</li>
 *   <li>MON-A05 에이전트 현재 작업 현황 — 에이전트 세션이 <b>명시 보고한</b> 현재 작업(agent_session.current_task_id)과 그 작업의 상태·
 *       대기 사유·검증 상태. 보고가 없으면 실행 중·완료를 추정하지 않는다(작업 칸이 빈 채로 세션만 나온다). 폐기된 토큰·활성이 아닌
 *       멤버의 세션은 뺀다. 연결 여부 자체는 AGT-A05 가 준다</li>
 * </ul>
 * 작업 관계(MON-06)는 관계 조회 API(MON-A03, -167)가 이미 제공한다. "현재 계획의 가장 최근 검증" 은 {@link ValidationStatusLookup} 한 곳의
 * 기준을 쓴다 (작업 상세·목록과 같은 값이 나오도록).
 */
@Service
public class TaskStatusQueryService {

    private final ProjectAccessChecker access;
    private final TaskStatusRepository repository;
    private final ValidationStatusLookup validations;

    public TaskStatusQueryService(ProjectAccessChecker access, TaskStatusRepository repository, ValidationStatusLookup validations) {
        this.access = access;
        this.repository = repository;
        this.validations = validations;
    }

    @Transactional(readOnly = true)
    public TaskSummary taskSummary(CallerContext caller, Long projectId) {
        access.requireMember(caller, projectId);
        Map<String, Integer> byStatus = repository.taskCountsByStatus(projectId);
        Map<String, Integer> byReason = repository.waitingCountsByReason(projectId);
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
        Map<String, Integer> byStatus = validations.countTasksByLatestStatus(projectId);
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
        List<Map<String, Object>> rows = repository.agentSessions(projectId, memberId, beforeId, size + 1);
        return PageResponse.fromRows(rows, size, row -> ((Number) row.get("session_id")).longValue(), page -> {
            // 보고된 작업들의 검증 상태는 한 번에 (작업 상세와 같은 기준)
            List<Long> taskIds = new ArrayList<>();
            for (Map<String, Object> row : page) {
                Long taskId = nullableLong(row.get("task_id"));
                if (taskId != null && !taskIds.contains(taskId)) {
                    taskIds.add(taskId);
                }
            }
            Map<Long, String> validationByTask = validations.latestStatusByTask(taskIds);
            List<AgentWork> items = new ArrayList<>();
            for (Map<String, Object> row : page) {
                Long taskId = nullableLong(row.get("task_id"));
                String taskStatus = null;
                String validationStatus = null;
                if (taskId != null) {
                    taskStatus = (String) row.get("task_status");
                    if (row.get("current_plan_revision_id") != null) {
                        validationStatus = validationByTask.getOrDefault(taskId, ValidationStatusLookup.NOT_RUN);
                    }
                }
                items.add(new AgentWork(Ids.str(((Number) row.get("member_id")).longValue()),
                        Ids.str(((Number) row.get("session_id")).longValue()), (String) row.get("agent_name"),
                        Ids.str(taskId), (String) row.get("task_title"), taskStatus, validationStatus,
                        (String) row.get("waiting_reason"), null));
            }
            return items;
        });
    }

    private static Long nullableLong(Object value) {
        if (value == null) {
            return null;
        }
        return ((Number) value).longValue();
    }
}
