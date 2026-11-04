package com.beez.backend.mcp.tool;

import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.mcp.dto.GetDocumentArgs;
import com.beez.backend.mcp.server.McpCall;
import com.beez.backend.mcp.server.McpTool;
import org.springframework.stereotype.Component;

/** MCP-02 get_document (02-mcp-tools). REST DOC-A02 와 같은 서비스(-192 {@link DocumentApplicationService#get})를 부른다 */
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
                프로젝트 문서의 원문을 읽는다. version_no 를 생략하면 가장 최근 승인본이다. \
                승인본이 없는 문서는 document_not_approved 오류다. version_no 를 지정하면 승인 전 버전도 나오므로, \
                근거로 쓰기 전에 version.status 가 APPROVED 이고 version.is_current_baseline 이 true 인지 확인한다.""";
    }

    @Override
    public String inputSchema() {
        return """
                {"type":"object","additionalProperties":false,"required":["document_id"],
                 "properties":{
                   "document_id":{"type":"string","description":"문서 ID"},
                   "version_no":{"type":"integer","minimum":1,"description":"문서 버전 번호. 생략하면 현재 승인본"}}}""";
    }

    @Override
    public Class<GetDocumentArgs> argsType() {
        return GetDocumentArgs.class;
    }

    @Override
    public Object call(McpCall call, GetDocumentArgs args) {
        return documents.get(call.caller(), call.projectId(), ToolArgs.requiredId(args.documentId(), "document_id"),
                args.versionNo());
    }
}
