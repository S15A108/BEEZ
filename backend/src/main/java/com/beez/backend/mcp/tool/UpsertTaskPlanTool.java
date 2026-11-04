package com.beez.backend.mcp.tool;

import com.beez.backend.mcp.dto.UpsertTaskPlanArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import com.beez.backend.mcp.service.TaskMcpService;
import org.springframework.stereotype.Component;

/** MCP-10 upsert_task_plan (02-mcp-tools). 계약: 08 문서 2절 */
@Component
class UpsertTaskPlanTool implements McpTool<UpsertTaskPlanArgs> {

    private final TaskMcpService tasks;

    UpsertTaskPlanTool(TaskMcpService tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "upsert_task_plan";
    }

    @Override
    public String description() {
        return """
                작업 계획을 저장한다. task_id 가 없으면 내 새 작업을 만들고, 있으면 그 작업의 새 계획 리비전을 만든다 \
                (이때 expected_revision_no·expected_lock_version 필수). design_refs 에는 get_development_context 로 받은 \
                정확한 설계 항목 버전을 넣는다. 저장 뒤 validate_task_plan 으로 검증한다. idempotency_key 는 필수다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["plan","idempotency_key"],
                 "properties":{
                   "plan":{"type":"object","additionalProperties":false,
                     "required":["title","goal","included_scope","excluded_scope","approach","completion_criteria",
                                 "dependency_task_ids","design_refs","unlinked","no_contract_change"],
                     "properties":{
                       "title":{"type":"string"},
                       "goal":{"type":"string"},
                       "included_scope":{"type":"array","items":{"type":"string"}},
                       "excluded_scope":{"type":"array","items":{"type":"string"}},
                       "approach":{"type":"string"},
                       "completion_criteria":{"type":"array","items":{"type":"string"}},
                       "dependency_task_ids":{"type":"array","items":{"type":"string"},"description":"선행 작업 ID"},
                       "design_refs":{"type":"array","items":{"type":"object","additionalProperties":false,
                         "required":["design_item_id","design_item_version_id","relation"],
                         "properties":{
                           "design_item_id":{"type":"string"},
                           "design_item_version_id":{"type":"string","description":"현재 기준 설계의 버전"},
                           "relation":{"type":"string","enum":["READS","IMPLEMENTS","CHANGES"]},
                           "proposed_content":{"type":"object","description":"CHANGES 에서 필수"}}}},
                       "unlinked":{"type":"array","items":{"type":"object"},"description":"설계에 연결하지 못한 내용. 예: {\\"text\\":\\"...\\"}"},
                       "no_contract_change":{"type":"boolean","description":"계약 변경 없음을 명시적으로 선언"},
                       "context_packet_id":{"type":"string","description":"근거 패킷 ID"}}},
                   "task_id":{"type":"string","description":"수정할 작업. 최초 생성은 생략"},
                   "expected_revision_no":{"type":"integer","description":"수정 시 필수: 현재 계획 번호"},
                   "expected_lock_version":{"type":"integer","description":"수정 시 필수: 작업 lock_version"},
                   "idempotency_key":{"type":"string","minLength":1}}}""";
    }

    @Override
    public Class<UpsertTaskPlanArgs> argsType() {
        return UpsertTaskPlanArgs.class;
    }

    @Override
    public Object call(McpCall call, UpsertTaskPlanArgs args) {
        return tasks.upsertTaskPlan(call.caller(), call.projectId(), args);
    }
}
