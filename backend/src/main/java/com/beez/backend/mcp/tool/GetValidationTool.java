package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.GetValidationArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.validation.service.ValidationQueryService;
import org.springframework.stereotype.Component;

/**
 * MCP-13 get_validation (02-mcp-tools). 08 문서 2절 "조회는 MCP-13(-179 연결 때 ValidationQueryService.get)".
 * REST VAL-A02 와 같은 서비스다. 이 도구가 없으면 validate_task_plan 이 RUNNING 일 때 에이전트가 결과를 볼 길이 없다.
 */
@Component
class GetValidationTool implements McpTool<GetValidationArgs> {

    private final ValidationQueryService validations;

    GetValidationTool(ValidationQueryService validations) {
        this.validations = validations;
    }

    @Override
    public String name() {
        return "get_validation";
    }

    @Override
    public String description() {
        return """
                validate_task_plan 으로 받은 검증 id 의 현재 결과를 본다. 조회는 새 검증을 만들지 않는다. \
                RUNNING 이면 잠시 뒤 다시 부른다. STALE·ERROR 면 계획을 확인하고 새 검증을 요청한다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["validation_id"],
                 "properties":{"validation_id":{"type":"string","description":"검증 ID"}}}""";
    }

    @Override
    public Class<GetValidationArgs> argsType() {
        return GetValidationArgs.class;
    }

    @Override
    public Object call(McpCall call, GetValidationArgs args) {
        return validations.get(call.caller(), call.projectId(), ToolArgs.requiredId(args.validationId(), "validation_id"));
    }
}
