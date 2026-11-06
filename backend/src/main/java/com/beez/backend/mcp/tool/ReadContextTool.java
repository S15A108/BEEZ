package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.ReadContextArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.ContextMcpService;
import org.springframework.stereotype.Component;

/** MCP-06 read_context (02-mcp-tools). 계약: 08 문서 3절. 패킷 조회도 이 도구로 한다(별도 패킷 도구 없음, -179 PR #40 연동 절) */
@Component
class ReadContextTool implements McpTool<ReadContextArgs> {

    private final ContextMcpService context;

    ReadContextTool(ContextMcpService context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "read_context";
    }

    @Override
    public String description() {
        return """
                근거 패킷(PACKET) 또는 작업 기록(RECORD) 하나를 저장 당시 내용 그대로 읽는다. \
                다른 프로젝트나 남의 비공개 기록은 not_found 다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["type","id"],
                 "properties":{
                   "type":{"type":"string","enum":["PACKET","RECORD"]},
                   "id":{"type":"string","description":"패킷 또는 기록 ID"}}}""";
    }

    @Override
    public Class<ReadContextArgs> argsType() {
        return ReadContextArgs.class;
    }

    @Override
    public Object call(McpCall call, ReadContextArgs args) {
        return context.readContext(call.caller(), call.projectId(), args.type(), args.id());
    }
}
