package com.beez.backend.mcp.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.InputValidator;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.mcp.dto.ValidateTaskPlanArgs;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.validation.dto.ValidationResponse;
import com.beez.backend.validation.service.ValidationAcceptService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP-11 validate_task_plan 업무 서비스 (S15P31A108-159). 어댑터(-179)가 토큰 인증 뒤 부른다 (08 연결 계약 2절).
 * 접수 규칙은 REST VAL-A01 과 같다({@link ValidationAcceptService}). 도구 결과에는 200/202 구분이 없고 status 로 본다.
 * 같은 키 재시도는 처음 결과를 그대로 돌려준다 (공통 5절) — 그 사이 담당자가 바뀌었으면 이전 담당자에게 주지 않는다.
 */
@Service
public class ValidationMcpService {

    private static final String OP_VALIDATE = "validate_task_plan";

    private final ValidationAcceptService accept;
    private final ProjectAccessChecker access;
    private final InputValidator validator;
    private final McpCommandStore commands;
    private final TaskRepository tasks;

    public ValidationMcpService(ValidationAcceptService accept, ProjectAccessChecker access, InputValidator validator,
                                McpCommandStore commands, TaskRepository tasks) {
        this.accept = accept;
        this.access = access;
        this.validator = validator;
        this.commands = commands;
        this.tasks = tasks;
    }

    @Transactional
    public ValidationResponse validateTaskPlan(CallerContext caller, Long projectId, ValidateTaskPlanArgs args) {
        // 권한이 첫 줄 (05 구현 메모)
        ProjectActor actor = access.requireMember(caller, projectId);
        validator.check(args, "arguments");
        validator.requireIdempotencyKey(args.idempotencyKey());
        Long taskId = Ids.parse(args.taskId(), "task_id");

        Optional<ValidationResponse> previous = commands.replay(projectId, actor.accountId(), OP_VALIDATE,
                args.idempotencyKey(), args, ValidationResponse.class);
        if (previous.isPresent()) {
            Optional<Task> task = tasks.findByIdAndProjectId(taskId, projectId);
            if (task.isEmpty()) {
                throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
            }
            actor.requireMemberId(task.get().getAssigneeMemberId(), "담당자만 검증을 요청할 수 있습니다");
            return previous.get();
        }
        ValidationResponse result = accept.accept(caller, projectId, taskId,
                Ids.parse(args.planRevisionId(), "plan_revision_id"), args.expectedLockVersion()).validation();
        commands.save(projectId, actor.accountId(), OP_VALIDATE, args.idempotencyKey(), args, result);
        return result;
    }
}
