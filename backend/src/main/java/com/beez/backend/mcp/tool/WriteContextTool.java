package com.beez.backend.mcp.tool;

import com.beez.backend.context.dto.WriteContextArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.ContextMcpService;
import org.springframework.stereotype.Component;

/** MCP-07 write_context (02-mcp-tools). 계약: 08 문서 3절 */
@Component
class WriteContextTool implements McpTool<WriteContextArgs> {

    private final ContextMcpService context;

    WriteContextTool(ContextMcpService context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "write_context";
    }

    @Override
    public String description() {
        return """
                팀과 나눌 작업 기록(결정·진행·이슈·인계)을 남긴다. 수정은 previous_id 로 내 이전 리비전을 이어 새 리비전을 만든다. \
                DECISION 도 승인 설계가 아니다 — 계획 변경은 upsert_task_plan, 설계 변경은 propose_design_change 로 한다. \
                비밀값을 적지 않는다. idempotency_key 는 필수다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,
                 "required":["kind","visibility","summary","detail","design_refs","idempotency_key"],
                 "properties":{
                   "previous_id":{"type":"string","description":"수정할 내 리비전. 최초 작성은 생략"},
                   "task_id":{"type":"string"},
                   "plan_revision_id":{"type":"string","description":"당시 계획 리비전"},
                   "source_packet_id":{"type":"string","description":"근거 패킷"},
                   "kind":{"type":"string","enum":["DECISION","PROGRESS","ISSUE","HANDOVER"]},
                   "visibility":{"type":"string","enum":["SHARED","PRIVATE"]},
                   "summary":{"type":"string","description":"검색용 공유 요약"},
                   "detail":{"type":"string"},
                   "design_refs":{"type":"array","description":"당시 참조한 설계 항목 버전. 없으면 빈 배열",
                     "items":{"type":"object","additionalProperties":false,"required":["design_item_id","design_item_version_id"],
                       "properties":{"design_item_id":{"type":"string"},"design_item_version_id":{"type":"string"}}}},
                   "idempotency_key":{"type":"string","minLength":1}}}""";
    }

    @Override
    public Class<WriteContextArgs> argsType() {
        return WriteContextArgs.class;
    }

    @Override
    public Object call(McpCall call, WriteContextArgs args) {
        return context.writeContext(call.caller(), call.projectId(), args);
    }
}
