package com.beez.backend.mcp.dto;

/** MCP-16 get_task 도구 인수 (02-mcp-tools MCP-16 요청 표, S15P31A108-335) */
public record GetTaskArgs(String taskId) {
}
