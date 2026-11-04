package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.GetDevelopmentContextArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.ContextMcpService;
import org.springframework.stereotype.Component;

/** MCP-01 get_development_context (02-mcp-tools). 계약: 08 문서 3절 — 도구 인수만 넘기고 패킷 구성은 서비스가 한다 */
@Component
class GetDevelopmentContextTool implements McpTool<GetDevelopmentContextArgs> {

    private final ContextMcpService context;

    GetDevelopmentContextTool(ContextMcpService context) {
        this.context = context;
    }

    @Override
    public String name() {
        return "get_development_context";
    }

    @Override
    public String description() {
        return """
                작업을 계획하기 전에 승인 설계·의존 설계·미확정 사항·관련 작업을 근거 패킷으로 받는다. 받은 패킷 ID 를 \
                upsert_task_plan 의 plan.context_packet_id 에 넣는다. 요청 문장에 비밀값을 넣지 않는다. \
                context_not_ready 오류는 구성 모듈이 준비되지 않은 것이지 근거가 없다는 뜻이 아니다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["request"],
                 "properties":{
                   "request":{"type":"string","description":"개발 요청 문장"},
                   "project_id":{"type":"string","description":"생략 가능. 주면 토큰 프로젝트와 같아야 한다"},
                   "task_id":{"type":"string","description":"관련 작업 ID"}}}""";
    }

    @Override
    public Class<GetDevelopmentContextArgs> argsType() {
        return GetDevelopmentContextArgs.class;
    }

    @Override
    public Object call(McpCall call, GetDevelopmentContextArgs args) {
        return context.getDevelopmentContext(call.caller(), call.projectId(), args);
    }
}
