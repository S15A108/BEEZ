package com.beez.backend.mcp.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.InputValidator;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designchange.dto.PlanChangesResponse;
import com.beez.backend.designchange.service.DesignChangesService;
import com.beez.backend.mcp.dto.ReportTaskStateArgs;
import com.beez.backend.mcp.dto.ReportTaskStateResult;
import com.beez.backend.mcp.dto.UpsertTaskPlanArgs;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.task.dto.TaskStateInput;
import com.beez.backend.task.service.TaskPlanService;
import com.beez.backend.task.service.TaskStateService;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 작업 도구가 부르는 업무 서비스 (S15P31A108-166). 계약은 docs/design/08-mcp-service-contract.md.
 * 규칙은 전부 REST 와 같은 서비스(-156 TaskPlanService, -158 TaskStateService, -164 DesignChangesService)에 있고,
 * 여기서는 도구 인수 모양에 맞추는 일만 한다:
 * <ul>
 *   <li>프로젝트는 토큰에서 정한 값을 어댑터가 넘긴다 (02-mcp-tools "연결과 인증")</li>
 *   <li>컨트롤러를 거치지 않으므로 DTO 검증 표시를 {@link InputValidator} 로 직접 적용한다</li>
 *   <li>idempotency_key: 같은 키·같은 입력은 처음 결과, 다른 입력은 409 idempotency_conflict ({@link McpCommandStore})</li>
 * </ul>
 */
@Service
public class TaskMcpService {

    private final TaskPlanService planService;
    private final TaskStateService stateService;
    private final DesignChangesService designChanges;
    private final ProjectAccessChecker access;
    private final InputValidator validator;
    private final McpCommandStore commands;
    private final TaskRepository tasks;

    private static final String OP_UPSERT = "upsert_task_plan";
    private static final String OP_REPORT = "report_task_state";

    public TaskMcpService(TaskPlanService planService, TaskStateService stateService, DesignChangesService designChanges,
                          ProjectAccessChecker access, InputValidator validator, McpCommandStore commands,
                          TaskRepository tasks) {
        this.planService = planService;
        this.stateService = stateService;
        this.designChanges = designChanges;
        this.access = access;
        this.validator = validator;
        this.commands = commands;
        this.tasks = tasks;
    }

    /**
     * MCP-10 upsert_task_plan. task_id 가 없으면 새 작업(TSK-A01), 있으면 새 계획 리비전(TSK-A04).
     * 수정이면 expected_revision_no·expected_lock_version 이 필수다 (명세 요청 표 "수정 시 필수").
     */
    @Transactional
    public TaskDetailResponse upsertTaskPlan(CallerContext caller, Long projectId, UpsertTaskPlanArgs args) {
        // 권한이 첫 줄 (05 구현 메모): 폐기 토큰·비멤버는 인수가 틀려도 401·404 를 받는다
        ProjectActor actor = access.requireMember(caller, projectId);
        validator.check(args, "arguments");
        validator.requireIdempotencyKey(args.idempotencyKey());
        validator.check(args.plan(), "plan");

        // 같은 키 재시도면 처음 결과를 그대로 돌려준다 (공통 5절). 멤버 확인은 첫 줄에서 했다
        Optional<TaskDetailResponse> previous = commands.replay(projectId, actor.accountId(), OP_UPSERT,
                args.idempotencyKey(), args, TaskDetailResponse.class);
        if (previous.isPresent()) {
            if (args.taskId() != null) {
                // 계획 수정 재시도: 그 사이 담당자가 바뀌었으면 이전 담당자에게 결과를 주지 않는다 (TaskPlanService 와 같은 규칙)
                requireCurrentAssignee(actor, projectId, Ids.parse(args.taskId(), "task_id"), "담당자만 계획을 바꿀 수 있습니다");
            }
            return previous.get();
        }

        TaskDetailResponse result;
        if (args.taskId() == null) {
            result = planService.createTask(caller, projectId, args.plan());
        } else {
            Long taskId = Ids.parse(args.taskId(), "task_id");
            if (args.expectedRevisionNo() == null || args.expectedLockVersion() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "수정 시 expected_revision_no 와 expected_lock_version 은 필수입니다");
            }
            result = planService.addRevision(caller, projectId, taskId, args.plan(),
                    args.expectedRevisionNo(), args.expectedLockVersion());
        }
        commands.save(projectId, actor.accountId(), OP_UPSERT, args.idempotencyKey(), args, result);
        return result;
    }

    /**
     * MCP-12 report_task_state. 전이는 TSK-A07 과 같은 규칙(TaskStateService)이고, 응답에 그 작업의 설계 변경 요약을 붙인다.
     * 전이와 변경 조회를 한 트랜잭션에서 한다 — 변경 요약은 전이가 반영된 상태를 기준으로 본다.
     */
    @Transactional
    public ReportTaskStateResult reportTaskState(CallerContext caller, Long projectId, ReportTaskStateArgs args) {
        // 권한이 첫 줄 (05 구현 메모)
        ProjectActor actor = access.requireMember(caller, projectId);
        validator.check(args, "arguments");
        validator.requireIdempotencyKey(args.idempotencyKey());
        if (args.taskId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "task_id 는 필수입니다");
        }
        Long taskId = Ids.parse(args.taskId(), "task_id");
        TaskStateInput state = new TaskStateInput(args.event(), args.planRevisionId(), args.expectedLockVersion(),
                args.validationId(), args.waitingReason(), args.summary(), args.delta(), args.completionChecks());
        validator.check(state, "arguments");

        // 같은 키 재시도면 처음 결과를 그대로 돌려준다 (공통 5절). 멤버 확인은 첫 줄에서 했다
        Optional<ReportTaskStateResult> previous = commands.replay(projectId, actor.accountId(), OP_REPORT,
                args.idempotencyKey(), args, ReportTaskStateResult.class);
        if (previous.isPresent()) {
            // 상태 보고 재시도: 지금도 그 작업의 담당자여야 한다 (TaskStateService 와 같은 규칙. MCP 는 에이전트만이라 팀장 취소 예외 없음)
            requireCurrentAssignee(actor, projectId, taskId, "담당자만 작업 상태를 바꿀 수 있습니다");
            return previous.get();
        }

        TaskDetailResponse task = stateService.report(caller, projectId, taskId, state);
        ReportTaskStateResult result;
        PlanChangesResponse planChanges = designChanges.changesSincePlan(actor, projectId, taskId);
        if (planChanges == null) {
            // 기준 설계가 한 번도 없는 프로젝트: changes 생략
            result = new ReportTaskStateResult(task, null, null);
        } else {
            result = new ReportTaskStateResult(task, planChanges.changes(), planChanges.sinceVersion());
        }
        commands.save(projectId, actor.accountId(), OP_REPORT, args.idempotencyKey(), args, result);
        return result;
    }

    /**
     * 같은 키 재시도에서 저장된 결과를 주기 전에 현재 작업 권한을 다시 본다 (공통 5절 "재요청도 현재 권한을 확인한다").
     * 다른 프로젝트·없는 작업은 404, 담당자가 아니면 403.
     */
    private void requireCurrentAssignee(ProjectActor actor, Long projectId, Long taskId, String message) {
        Optional<Task> task = tasks.findByIdAndProjectId(taskId, projectId);
        if (task.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        actor.requireMemberId(task.get().getAssigneeMemberId(), message);
    }
}
