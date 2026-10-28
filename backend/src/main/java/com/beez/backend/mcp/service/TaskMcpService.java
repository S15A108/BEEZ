package com.beez.backend.mcp.service;

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
import com.beez.backend.task.dto.TaskStateInput;
import com.beez.backend.task.service.TaskPlanService;
import com.beez.backend.task.service.TaskStateService;
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
 *   <li>idempotency_key 는 필수 확인만 한다 (Q12 확정 전, 다른 PR 과 같음). 같은 키로 재시도하면 작업·리비전이 한 번 더 생긴다 — 알려진 한계</li>
 * </ul>
 */
@Service
public class TaskMcpService {

    private final TaskPlanService planService;
    private final TaskStateService stateService;
    private final DesignChangesService designChanges;
    private final ProjectAccessChecker access;
    private final InputValidator validator;

    public TaskMcpService(TaskPlanService planService, TaskStateService stateService, DesignChangesService designChanges,
                          ProjectAccessChecker access, InputValidator validator) {
        this.planService = planService;
        this.stateService = stateService;
        this.designChanges = designChanges;
        this.access = access;
        this.validator = validator;
    }

    /**
     * MCP-10 upsert_task_plan. task_id 가 없으면 새 작업(TSK-A01), 있으면 새 계획 리비전(TSK-A04).
     * 수정이면 expected_revision_no·expected_lock_version 이 필수다 (명세 요청 표 "수정 시 필수").
     */
    @Transactional
    public TaskDetailResponse upsertTaskPlan(CallerContext caller, Long projectId, UpsertTaskPlanArgs args) {
        validator.check(args, "arguments");
        validator.requireIdempotencyKey(args.idempotencyKey());
        validator.check(args.plan(), "plan");

        if (args.taskId() == null) {
            return planService.createTask(caller, projectId, args.plan());
        }
        Long taskId = Ids.parse(args.taskId(), "task_id");
        if (args.expectedRevisionNo() == null || args.expectedLockVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                    "수정 시 expected_revision_no 와 expected_lock_version 은 필수입니다");
        }
        return planService.addRevision(caller, projectId, taskId, args.plan(),
                args.expectedRevisionNo(), args.expectedLockVersion());
    }

    /**
     * MCP-12 report_task_state. 전이는 TSK-A07 과 같은 규칙(TaskStateService)이고, 응답에 그 작업의 설계 변경 요약을 붙인다.
     * 전이와 변경 조회를 한 트랜잭션에서 한다 — 변경 요약은 전이가 반영된 상태를 기준으로 본다.
     */
    @Transactional
    public ReportTaskStateResult reportTaskState(CallerContext caller, Long projectId, ReportTaskStateArgs args) {
        validator.check(args, "arguments");
        validator.requireIdempotencyKey(args.idempotencyKey());
        if (args.taskId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "task_id 는 필수입니다");
        }
        Long taskId = Ids.parse(args.taskId(), "task_id");
        TaskStateInput state = new TaskStateInput(args.event(), args.planRevisionId(), args.expectedLockVersion(),
                args.validationId(), args.waitingReason(), args.summary(), args.delta(), args.completionChecks());
        validator.check(state, "arguments");

        TaskDetailResponse task = stateService.report(caller, projectId, taskId, state);
        // 권한은 report 안에서 이미 확인했다. 변경 조회 이력에 쓸 호출자 정보를 얻으려고 한 번 더 부른다 (캐시 없이 같은 결과)
        ProjectActor actor = access.requireMember(caller, projectId);
        PlanChangesResponse planChanges = designChanges.changesSincePlan(actor, projectId, taskId);
        if (planChanges == null) {
            // 기준 설계가 한 번도 없는 프로젝트: changes 생략
            return new ReportTaskStateResult(task, null, null);
        }
        return new ReportTaskStateResult(task, planChanges.changes(), planChanges.sinceVersion());
    }
}
