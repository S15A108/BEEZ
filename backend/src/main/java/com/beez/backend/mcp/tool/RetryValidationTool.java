package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.RetryValidationArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.ValidationMcpService;
import org.springframework.stereotype.Component;

/**
 * MCP-14 retry_validation (02-mcp-tools). 계약: 08 문서 2절 — {@code ValidationMcpService.retryValidation}(-161),
 * REST VAL-A04 와 같은 {@code ValidationRetryService} 규칙이다. 도구명·분리는 02-mcp-tools 상태("담당자 검토")대로 두고 명세 이름을 쓴다.
 */
@Component
class RetryValidationTool implements McpTool<RetryValidationArgs> {

    private final ValidationMcpService validations;

    RetryValidationTool(ValidationMcpService validations) {
        this.validations = validations;
    }

    @Override
    public String name() {
        return "retry_validation";
    }

    @Override
    public String description() {
        return """
                ERROR 로 끝난 검증을 같은 입력으로 다시 실행한다. 현재 시도의 실행 오류가 모두 RETRY 일 때만 받는다 — \
                HUMAN_REVIEW 오류나 STALE·계획 변경이면 계획을 보완하고 validate_task_plan 으로 새 검증을 요청한다. \
                expected_attempt 는 get_validation 의 current_attempt 다. 결과가 RUNNING 이면 get_validation 으로 본다. \
                idempotency_key 는 필수다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,
                 "required":["validation_id","expected_attempt","idempotency_key"],
                 "properties":{
                   "validation_id":{"type":"string","description":"ERROR 검증 ID"},
                   "expected_attempt":{"type":"integer","minimum":1,"description":"현재 시도 (current_attempt)"},
                   "idempotency_key":{"type":"string","minLength":1}}}""";
    }

    @Override
    public Class<RetryValidationArgs> argsType() {
        return RetryValidationArgs.class;
    }

    @Override
    public Object call(McpCall call, RetryValidationArgs args) {
        return validations.retryValidation(call.caller(), call.projectId(), args);
    }
}
