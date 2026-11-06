package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.ReportTaskStateArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.TaskMcpService;
import org.springframework.stereotype.Component;

/** MCP-12 report_task_state (02-mcp-tools). 계약: 08 문서 2절 */
@Component
class ReportTaskStateTool implements McpTool<ReportTaskStateArgs> {

    private final TaskMcpService tasks;

    ReportTaskStateTool(TaskMcpService tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "report_task_state";
    }

    @Override
    public String description() {
        return """
                작업 상태 전이를 보고한다. START·RESUME 는 PASS 한 validation_id 가 필요하다. WAIT 는 waiting_reason 이 필요하다. \
                COMPLETED 에 계획 대비 변경(delta)이 있으면 재검증을 거쳐 서버가 완료로 바꾼다. COMPLETED 에 changed_design_item_ids 를 보내면 결과 task.completion_review 에 계획과 대조한 범위 이탈 후보가 나온다(표시만, 품질 보증 아님). summary 는 대시보드에 그대로 보인다. \
                결과의 changes 는 이 작업 계획 이후 발행된 설계 변경이다 — has_more 이면 get_design_changes 로 이어 본다. \
                idempotency_key 는 필수다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,
                 "required":["task_id","event","plan_revision_id","expected_lock_version","idempotency_key"],
                 "properties":{
                   "task_id":{"type":"string","description":"내 작업"},
                   "event":{"type":"string","enum":["START","RESUME","WAIT","COMPLETED","CANCEL"]},
                   "plan_revision_id":{"type":"string","description":"현재 계획 리비전"},
                   "expected_lock_version":{"type":"integer","description":"조회한 lock_version"},
                   "validation_id":{"type":"string","description":"START·RESUME 필수"},
                   "waiting_reason":{"type":"string","enum":["INPUT","APPROVAL","DEPENDENCY"],"description":"WAIT 필수"},
                   "summary":{"type":"string","description":"진행·완료 요약"},
                   "delta":{"type":"array","items":{"type":"object"},"description":"완료 시 계획 대비 변경"},
                   "completion_checks":{"type":"array","items":{"type":"object","additionalProperties":false,
                     "required":["criterion","met"],
                     "properties":{"criterion":{"type":"string"},"met":{"type":"boolean"}}}},
                   "changed_design_item_ids":{"type":"array","items":{"type":"string"},"maxItems":200,
                     "description":"COMPLETED 에서만. 실제로 바꾼 설계 항목. 계획과 대조해 범위 이탈 후보를 결과에 보여 준다(완료는 막지 않음)"},
                   "idempotency_key":{"type":"string","minLength":1}}}""";
    }

    @Override
    public Class<ReportTaskStateArgs> argsType() {
        return ReportTaskStateArgs.class;
    }

    @Override
    public Object call(McpCall call, ReportTaskStateArgs args) {
        return tasks.reportTaskState(call.caller(), call.projectId(), args);
    }
}
