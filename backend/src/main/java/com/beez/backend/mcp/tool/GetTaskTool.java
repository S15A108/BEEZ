package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.GetTaskArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.task.service.TaskQueryService;
import org.springframework.stereotype.Component;

/**
 * MCP-16 get_task (02-mcp-tools, S15P31A108-335). REST TSK-A03 과 같은 {@link TaskQueryService#getTaskDetail} 이다 —
 * 권한(현재 멤버)·소속 거부(다른 프로젝트 작업은 AccessErrors 404, 감사 DENIED)도 그대로 따라온다.
 * 이 도구가 없으면 작업을 넘겨받은 새 에이전트가 validate_task_plan·report_task_state 에 넣을 lock_version 과
 * 현재 계획을 MCP 로 알 길이 없다(get_validation 은 검증 id 가 있어야 하고 계획 리비전 id 까지만 준다).
 */
@Component
class GetTaskTool implements McpTool<GetTaskArgs> {

    private final TaskQueryService tasks;

    GetTaskTool(TaskQueryService tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "get_task";
    }

    @Override
    public String description() {
        return """
                작업의 현재 상태·lock_version·현재 계획(리비전 id·번호·본문)·현재 계획의 최근 검증을 본다. 조회는 아무것도 바꾸지 않는다. \
                작업을 넘겨받았거나 revision_conflict 를 받았으면 이 도구로 lock_version 과 current_plan_revision 을 다시 읽고, \
                그 값으로 validate_task_plan(expected_lock_version) 을 다시 요청한 뒤 report_task_state(RESUME) 로 이어 간다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["task_id"],
                 "properties":{"task_id":{"type":"string","description":"작업 ID"}}}""";
    }

    @Override
    public Class<GetTaskArgs> argsType() {
        return GetTaskArgs.class;
    }

    @Override
    public Object call(McpCall call, GetTaskArgs args) {
        return tasks.getTaskDetail(call.caller(), call.projectId(), ToolArgs.requiredId(args.taskId(), "task_id"));
    }
}
