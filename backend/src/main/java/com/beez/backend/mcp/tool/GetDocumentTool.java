package com.beez.backend.mcp.tool;

import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.mcp.dto.GetDocumentArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import org.springframework.stereotype.Component;

/**
 * MCP-02 get_document (02-mcp-tools). MCP 전용 조회 {@link DocumentApplicationService#getForAgent}를 부른다 —
 * 기준 버전 선택·허용 범위·조회 이력은 서비스가 정한다. REST DOC-A02({@code get})는 문서 검토용이라 쓰지 않는다.
 */
@Component
class GetDocumentTool implements McpTool<GetDocumentArgs> {

    private final DocumentApplicationService documents;

    GetDocumentTool(DocumentApplicationService documents) {
        this.documents = documents;
    }

    @Override
    public String name() {
        return "get_document";
    }

    @Override
    public String description() {
        return """
                승인 문서의 원문을 읽는다. version_no 를 생략하면 현재 기준 설계에 들어간 버전이다. \
                version_no 를 지정하면 승인된 적 있는 버전만 읽을 수 있고, 지난 버전은 version.is_current_baseline 이 false 다. \
                현재 기준에 없는 문서나 승인 전 버전은 not_found 오류다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["document_id"],
                 "properties":{
                   "document_id":{"type":"string","description":"문서 ID"},
                   "version_no":{"type":"integer","minimum":1,"description":"문서 버전 번호. 생략하면 현재 기준 설계의 버전"}}}""";
    }

    @Override
    public Class<GetDocumentArgs> argsType() {
        return GetDocumentArgs.class;
    }

    @Override
    public Object call(McpCall call, GetDocumentArgs args) {
        return documents.getForAgent(call.caller(), call.projectId(),
                ToolArgs.requiredId(args.documentId(), "document_id"), args.versionNo());
    }
}
