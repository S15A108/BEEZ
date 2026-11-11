package com.beez.backend.mcp.tool;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.service.TaskQueryService;
import java.util.List;
import org.springframework.stereotype.Component;

/** MCP-17 list_my_tasks (S15P31A108-390). */
@Component
class ListMyTasksTool implements McpTool<ListMyTasksTool.Args> {

    private static final List<TaskStatus> OPEN = List.of(
            TaskStatus.DRAFT, TaskStatus.READY, TaskStatus.IN_PROGRESS, TaskStatus.WAITING);

    private final ProjectAccessChecker access;
    private final TaskQueryService tasks;

    ListMyTasksTool(ProjectAccessChecker access, TaskQueryService tasks) {
        this.access = access;
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "list_my_tasks";
    }

    @Override
    public String description() {
        return """
                연결된 프로젝트에서 내게 배정된 미완료 작업을 본다. has_more 이면 next_cursor 로 이어 읽는다. \
                맡은 작업을 진행할 때 get_task 로 현재 계획과 잠금 번호를 확인하고 같은 task_id 에 계획을 저장한다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"properties":{
                  "cursor":{"type":"string","description":"이전 응답의 next_cursor"},
                  "limit":{"type":"integer","minimum":1,"maximum":100,"description":"페이지 크기. 기본 20"}}}""";
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public Object call(McpCall call, Args args) {
        long memberId = access.requireMember(call.caller(), call.projectId()).memberId();
        return tasks.listTasks(call.caller(), call.projectId(), OPEN, null, memberId, false, args.cursor(), args.limit());
    }

    public record Args(String cursor, Integer limit) {}
}
