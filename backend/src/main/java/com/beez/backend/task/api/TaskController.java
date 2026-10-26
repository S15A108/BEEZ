package com.beez.backend.task.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.AddPlanRevisionRequest;
import com.beez.backend.task.dto.ChangeAssigneeRequest;
import com.beez.backend.task.dto.PageResponse;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.dto.TaskPlanRevisionResponse;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.service.TaskAssigneeService;
import com.beez.backend.task.service.TaskPlanService;
import com.beez.backend.task.service.TaskQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * TSK-A01 ~ A06. 컨트롤러는 HTTP ↔ 서비스 변환만 한다. 규칙·권한은 전부 서비스 안에 있다 (MCP 어댑터가 같은 서비스를 쓴다).
 * 호출자는 {@link CallerContext} 인자로 들어온다 (CallerContextArgumentResolver).
 */
@Tag(name = "task", description = "작업 계획·조회 (TSK)")
@RestController
@RequestMapping("/api/projects/{id}/tasks")
public class TaskController {

    private final TaskPlanService planService;
    private final TaskQueryService queryService;
    private final TaskAssigneeService assigneeService;

    public TaskController(TaskPlanService planService, TaskQueryService queryService,
                          TaskAssigneeService assigneeService) {
        this.planService = planService;
        this.queryService = queryService;
        this.assigneeService = assigneeService;
    }

    @Operation(summary = "TSK-A01 작업 생성 (첫 계획 리비전 포함, READY)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetailResponse create(CallerContext caller, @PathVariable("id") Long projectId,
                                     @Valid @RequestBody TaskPlanInput body) {
        return planService.createTask(caller, projectId, body);
    }

    @Operation(summary = "TSK-A02 작업 목록 (커서)")
    @GetMapping
    public PageResponse<TaskDetailResponse> list(CallerContext caller, @PathVariable("id") Long projectId,
                                                 @RequestParam(name = "status", required = false) TaskStatus status,
                                                 @RequestParam(name = "validation_status", required = false) String validationStatus,
                                                 @RequestParam(name = "assignee_member_id", required = false) Long assigneeMemberId,
                                                 @RequestParam(name = "cursor", required = false) String cursor,
                                                 @RequestParam(name = "limit", required = false) Integer limit) {
        return queryService.listTasks(caller, projectId, status, validationStatus, assigneeMemberId, cursor, limit);
    }

    @Operation(summary = "TSK-A03 작업 상세")
    @GetMapping("/{task_id}")
    public TaskDetailResponse get(CallerContext caller, @PathVariable("id") Long projectId,
                                  @PathVariable("task_id") Long taskId) {
        return queryService.getTask(caller, projectId, taskId);
    }

    @Operation(summary = "TSK-A04 계획 리비전 추가 (덮어쓰지 않음)")
    @PostMapping("/{task_id}/plan-revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDetailResponse addRevision(CallerContext caller, @PathVariable("id") Long projectId,
                                          @PathVariable("task_id") Long taskId,
                                          @Valid @RequestBody AddPlanRevisionRequest body) {
        return planService.addRevision(caller, projectId, taskId, body.toPlanInput(),
                body.expectedRevisionNo(), body.expectedLockVersion());
    }

    @Operation(summary = "TSK-A05 계획 리비전 목록 (당시 참조 버전 그대로)")
    @GetMapping("/{task_id}/plan-revisions")
    public PageResponse<TaskPlanRevisionResponse> listRevisions(CallerContext caller, @PathVariable("id") Long projectId,
                                                                @PathVariable("task_id") Long taskId,
                                                                @RequestParam(name = "cursor", required = false) String cursor,
                                                                @RequestParam(name = "limit", required = false) Integer limit) {
        return queryService.listRevisions(caller, projectId, taskId, cursor, limit);
    }

    @Operation(summary = "TSK-A06 담당자 변경 (사람 팀장만)")
    @PatchMapping("/{task_id}/assignee")
    public TaskDetailResponse changeAssignee(CallerContext caller, @PathVariable("id") Long projectId,
                                             @PathVariable("task_id") Long taskId,
                                             @Valid @RequestBody ChangeAssigneeRequest body) {
        return assigneeService.changeAssignee(caller, projectId, taskId, body.assigneeMemberId(),
                body.expectedLockVersion());
    }
}
