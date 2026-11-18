package com.beez.backend.common.access;

/**
 * /mcp 프로젝트 토큰으로 인증된 주체. 프로젝트는 토큰에서 정해진다 (MCP "연결과 인증").
 * {@link com.beez.backend.agenttoken.StoredAgentTokenVerifier} 가 토큰을 검증해 만든다.
 */
public record AgentPrincipal(long accountId, long projectId, long agentTokenId) {
}
