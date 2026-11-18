package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.ValidateTaskPlanArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.ValidationMcpService;
import org.springframework.stereotype.Component;

/** MCP-11 validate_task_plan (02-mcp-tools). 계약: 08 문서 2절 */
@Component
class ValidateTaskPlanTool implements McpTool<ValidateTaskPlanArgs> {

    private final ValidationMcpService validations;

    ValidateTaskPlanTool(ValidationMcpService validations) {
        this.validations = validations;
    }

    @Override
    public String name() {
        return "validate_task_plan";
    }

    @Override
    public String description() {
        return """
                현재 계획의 검증을 요청한다. 결과 status 가 PASS 일 때만 report_task_state(START) 로 간다. \
                RUNNING 이면 같은 id 로 get_validation 을 불러 끝날 때까지 본다. NEEDS_* 이면 계획을 보완한다. \
                idempotency_key 는 필수다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,
                 "required":["task_id","plan_revision_id","expected_lock_version","idempotency_key"],
                 "properties":{
                   "task_id":{"type":"string","description":"내 작업"},
                   "plan_revision_id":{"type":"string","description":"현재 계획 리비전"},
                   "expected_lock_version":{"type":"integer","description":"작업 lock_version"},
                   "idempotency_key":{"type":"string","minLength":1}}}""";
    }

    @Override
    public Class<ValidateTaskPlanArgs> argsType() {
        return ValidateTaskPlanArgs.class;
    }

    @Override
    public Object call(McpCall call, ValidateTaskPlanArgs args) {
        return validations.validateTaskPlan(call.caller(), call.projectId(), args);
    }
}
