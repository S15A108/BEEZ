package com.beez.backend.task.api;

import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskStatusResponses.AgentWork;
import com.beez.backend.task.dto.TaskStatusResponses.TaskSummary;
import com.beez.backend.task.dto.TaskStatusResponses.ValidationSummary;
import com.beez.backend.task.service.TaskStatusQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업·검증·에이전트 현황 조회 (S15P31A108-64). 알림·활동 이벤트 전달은 공통 모듈(-181·-182) 뒤에 붙인다.
 * `/tasks/summary` 는 `/tasks/{task_id}` 보다 글자 그대로 맞는 경로가 먼저라 충돌하지 않는다 (`/validations/summary` 도 같다).
 */
@RestController
@Tag(name = "task", description = "작업 계획·조회 (TSK)")
@RequestMapping("/api/projects/{id}")
public class TaskStatusController {

    private final TaskStatusQueryService status;

    public TaskStatusController(TaskStatusQueryService status) {
        this.status = status;
    }

    @Operation(summary = "MON-T01 작업 상태 현황")
    @GetMapping("/tasks/summary")
    public TaskSummary tasks(CallerContext caller, @PathVariable("id") Long projectId) {
        return status.taskSummary(caller, projectId);
    }

    @Operation(summary = "MON-V01 검증 상태 현황")
    @GetMapping("/validations/summary")
    public ValidationSummary validations(CallerContext caller, @PathVariable("id") Long projectId) {
        return status.validationSummary(caller, projectId);
    }

    @Operation(summary = "MON-A05 에이전트 현재 작업 현황")
    @GetMapping("/agents/work-status")
    public PageResponse<AgentWork> agents(CallerContext caller, @PathVariable("id") Long projectId,
                                          @RequestParam(value = "member_id", required = false) String memberId,
                                          @RequestParam(value = "cursor", required = false) String cursor,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        return status.agentWorkStatus(caller, projectId, Ids.parse(memberId, "member_id"), cursor, limit);
    }
}
