package com.beez.backend.mcp.tool;

import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.mcp.dto.ProposeDesignChangeArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP-04 propose_design_change (02-mcp-tools). REST CHG-A01 과 같은 서비스(-195 {@link ProposalCommandService#create})를 부른다.
 * 스키마에는 지금 서비스가 받는 칸만 싣는다 — 명세의 수정안 요청 칸(items[].proposed_text, meeting_id)은 서비스에 아직 없다.
 */
@Component
class ProposeDesignChangeTool implements McpTool<ProposeDesignChangeArgs> {

    private final ProposalCommandService proposals;

    ProposeDesignChangeTool(ProposalCommandService proposals) {
        this.proposals = proposals;
    }

    @Override
    public String name() {
        return "propose_design_change";
    }

    @Override
    public String description() {
        return """
                승인 설계를 바꿔야 할 때 변경 제안을 제출한다. 제출만으로 설계·작업은 바뀌지 않고, 팀장이 승인·발행한다. \
                base_baseline_id 는 현재 기준 설계여야 한다. items 와 documents 는 바꿀 것이 없으면 빈 배열로 보낸다. \
                idempotency_key 는 필수이며 같은 키로 다시 부르면 처음 결과를 받는다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,
                 "required":["base_baseline_id","title","reason","items","documents","idempotency_key"],
                 "properties":{
                   "base_baseline_id":{"type":"string","description":"제안 기준 설계 ID"},
                   "title":{"type":"string"},
                   "reason":{"type":"string","description":"변경 이유"},
                   "task_id":{"type":"string","description":"관련 작업"},
                   "validation_id":{"type":"string","description":"관련 검증"},
                   "previous_proposal_id":{"type":"string","description":"거둔(WITHDRAWN) 본인 제안을 다시 올릴 때 이전 제안"},
                   "items":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["operation"],
                     "properties":{
                       "operation":{"type":"string","enum":["CREATE","UPDATE","RETIRE"]},
                       "design_item_id":{"type":"string","description":"UPDATE·RETIRE 필수"},
                       "base_design_item_version_id":{"type":"string","description":"UPDATE·RETIRE 필수 기준 버전"},
                       "kind":{"type":"string","enum":["REQUIREMENT","API_CONTRACT","DATA_CONTRACT","POLICY","CONSTRAINT"],
                               "description":"CREATE 필수"},
                       "schema_version":{"type":"integer","description":"CREATE·UPDATE 필수"},
                       "proposed_content":{"type":"object","description":"CREATE·UPDATE 필수 본문"}}}},
                   "documents":{"type":"array","items":{"type":"object","additionalProperties":false,
                     "required":["document_id","base_document_version_id"],
                     "properties":{
                       "document_id":{"type":"string"},
                       "base_document_version_id":{"type":"string"},
                       "after_content":{"type":"string","description":"텍스트 수정안. proposed_document_version_id 와 둘 중 하나"},
                       "proposed_document_version_id":{"type":"string","description":"업로드한 DRAFT 버전"}}}},
                   "idempotency_key":{"type":"string","minLength":1,"maxLength":128}}}""";
    }

    @Override
    public Class<ProposeDesignChangeArgs> argsType() {
        return ProposeDesignChangeArgs.class;
    }

    @Override
    public Object call(McpCall call, ProposeDesignChangeArgs args) {
        return proposals.create(call.caller(), call.projectId(), args.toInput(), args.idempotencyKey());
    }
}
