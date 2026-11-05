package com.beez.backend.mcp.tool;

import com.beez.backend.designchange.dto.DesignChangesQuery;
import com.beez.backend.designchange.service.DesignChangesService;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import org.springframework.stereotype.Component;

/** MCP-03 get_design_changes (02-mcp-tools). 계약: 08 문서 2절 — {@link DesignChangesService#getChanges} */
@Component
class GetDesignChangesTool implements McpTool<DesignChangesQuery> {

    private final DesignChangesService changes;

    GetDesignChangesTool(DesignChangesService changes) {
        this.changes = changes;
    }

    @Override
    public String name() {
        return "get_design_changes";
    }

    @Override
    public String description() {
        return """
                마지막으로 확인한 기준 번호 이후 발행된 설계 변경과 작업 영향·검증 현재성을 본다. 처음이면 since_version=0. \
                has_more 이면 next_cursor 로 끝까지 읽은 뒤 기준 번호를 갱신한다. report_task_state 의 changes.has_more 이면 \
                since_version=changes_since_version, 같은 task_id, cursor=changes.next_cursor 로 이어 본다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["since_version"],
                 "properties":{
                   "since_version":{"type":"integer","minimum":0,"description":"마지막으로 확인한 기준 번호. 최초 0"},
                   "task_id":{"type":"string","description":"작업 범위"},
                   "cursor":{"type":"string","description":"이전 응답의 next_cursor"},
                   "limit":{"type":"integer","minimum":1,"maximum":100,"description":"페이지 크기. 기본 20"}}}""";
    }

    @Override
    public Class<DesignChangesQuery> argsType() {
        return DesignChangesQuery.class;
    }

    @Override
    public Object call(McpCall call, DesignChangesQuery args) {
        return changes.getChanges(call.caller(), call.projectId(), args);
    }
}
