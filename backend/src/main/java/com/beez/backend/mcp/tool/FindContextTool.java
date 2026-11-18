package com.beez.backend.mcp.tool;

import com.beez.backend.context.dto.FindContextArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.ContextMcpService;
import org.springframework.stereotype.Component;

/** MCP-05 find_context (02-mcp-tools). 계약: 08 문서 3절 — 요약 문자열 검색 최소 범위(-166) */
@Component
class FindContextTool implements McpTool<FindContextArgs> {

    private final ContextMcpService context;

    FindContextTool(ContextMcpService context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "find_context";
    }

    @Override
    public String description() {
        return """
                팀이 남긴 작업 기록(결정·진행·이슈·인계)을 요약 문자열로 찾는다. 공유 기록과 내 비공개 기록만 나온다. \
                모든 리비전이 나오며 is_latest_revision 으로 최신을 구분한다. 상세는 read_context(type=RECORD) 로 읽는다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["query"],
                 "properties":{
                   "query":{"type":"string","description":"검색어 (요약에 포함, 대소문자 무시)"},
                   "task_id":{"type":"string","description":"작업"},
                   "kinds":{"type":"array","items":{"type":"string","enum":["DECISION","PROGRESS","ISSUE","HANDOVER"]}},
                   "cursor":{"type":"string","description":"이전 응답의 next_cursor"},
                   "limit":{"type":"integer","minimum":1,"maximum":100,"description":"페이지 크기. 기본 20"}}}""";
    }

    @Override
    public Class<FindContextArgs> argsType() {
        return FindContextArgs.class;
    }

    @Override
    public Object call(McpCall call, FindContextArgs args) {
        return context.findContext(call.caller(), call.projectId(), args);
    }
}
