package com.beez.backend.mcp.dto;

/** MCP-15 check_connection 인수. 받는 칸이 없다 — 프로젝트·계정은 토큰에서 정해진다. 모르는 키는 거부된다 (-179 결정) */
public record CheckConnectionArgs() {
}
