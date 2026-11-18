package com.beez.backend.mcp.dto;

/**
 * MCP-02 get_document 도구 인수 (02-mcp-tools MCP-02 요청 표).
 *
 * @param documentId 문서 (필수)
 * @param versionNo  문서 버전 번호. 생략하면 현재 승인본
 */
public record GetDocumentArgs(String documentId, Integer versionNo) {
}
