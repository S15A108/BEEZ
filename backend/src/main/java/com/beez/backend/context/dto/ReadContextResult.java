package com.beez.backend.context.dto;

/** MCP-06 read_context 응답. type 과 맞는 쪽 하나만 채운다 */
public record ReadContextResult(String type, ContextPacketResponse packet, ContextRecordResponse record) {
}
