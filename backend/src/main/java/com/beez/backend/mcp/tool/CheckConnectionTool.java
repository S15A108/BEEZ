package com.beez.backend.mcp.tool;

import com.beez.backend.agenttoken.AgentSessionService;
import com.beez.backend.mcp.dto.CheckConnectionArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP-15 check_connection (02-mcp-tools, -178). 연결 시험 — 이 토큰·세션이 누구로 어느 프로젝트에 붙었는지 돌려준다.
 * 다른 도구와 같은 토큰 검증·권한 검사를 거치므로, 이 도구가 성공하면 업무 도구도 같은 조건으로 부를 수 있다.
 */
@Component
class CheckConnectionTool implements McpTool<CheckConnectionArgs> {

    private final AgentSessionService sessions;

    CheckConnectionTool(AgentSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public String name() {
        return "check_connection";
    }

    @Override
    public String description() {
        return """
                BEEZ 연결을 확인한다. 이 토큰의 프로젝트·계정·토큰 이름·MCP 세션을 돌려준다. 아무것도 바꾸지 않는다. \
                연결을 처음 설정했거나 다른 도구가 인증 오류를 낼 때 먼저 부른다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"properties":{}}""";
    }

    @Override
    public Class<CheckConnectionArgs> argsType() {
        return CheckConnectionArgs.class;
    }

    @Override
    public Object call(McpCall call, CheckConnectionArgs args) {
        return sessions.check(call.caller(), call.projectId(), call.sessionId());
    }
}
