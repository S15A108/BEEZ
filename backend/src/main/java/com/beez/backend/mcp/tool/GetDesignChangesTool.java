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
                since_version=changes_since_version, 같은 task_id, cursor=changes.next_cursor 로 이어 본다. \
                발행 후 영향 분석 결과는 impact_updates 로 따로 온다. 처음에는 result_cursor 없이 불러 next_result_cursor 를 받고, \
                다음부터 그 값을 보낸다. result_has_more 이면 next_result_cursor 로 이어 본다. \
                impact_updates 의 후보는 승인된 설계가 아니고 작업 상태를 바꾸지 않는다 — 확인할 거리다. \
                candidates_truncated 가 0 보다 크면 전체 후보는 대시보드에서 본다. NOT_EVALUATED·FAILED 는 영향 0건이 아니다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["since_version"],
                 "properties":{
                   "since_version":{"type":"integer","minimum":0,"description":"마지막으로 확인한 기준 번호. 최초 0"},
                   "task_id":{"type":"string","description":"작업 범위"},
                   "cursor":{"type":"string","description":"이전 응답의 next_cursor"},
                   "limit":{"type":"integer","minimum":1,"maximum":100,"description":"페이지 크기. 기본 20"},
                   "result_cursor":{"type":"string","description":"이전 응답의 next_result_cursor. 처음이면 생략(지금부터)"}}}""";
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
