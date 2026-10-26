package com.beez.backend.common.access;

/**
 * /mcp 프로젝트 토큰으로 인증된 주체. 프로젝트는 토큰에서 정해진다 (MCP "연결과 인증").
 * 토큰 발급·검증 본체는 -175/-176 이 {@code AgentTokenVerifier} 를 구현해 채운다.
 */
public record AgentPrincipal(long accountId, long projectId, long agentTokenId) {
}
