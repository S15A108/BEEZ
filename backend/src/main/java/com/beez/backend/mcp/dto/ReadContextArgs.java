package com.beez.backend.mcp.dto;

/**
 * MCP-06 read_context 도구 인수 (02-mcp-tools MCP-06 요청 표). type 값 확인(PACKET|RECORD)은 서비스가 한다.
 *
 * @param type PACKET 또는 RECORD
 * @param id   패킷 또는 기록 ID
 */
public record ReadContextArgs(String type, String id) {
}
