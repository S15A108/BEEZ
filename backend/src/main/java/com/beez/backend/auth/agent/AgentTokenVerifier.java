package com.beez.backend.auth.agent;

import com.beez.backend.common.access.AgentPrincipal;

/**
 * /mcp Bearer 토큰 검증. 구현은 {@link com.beez.backend.agenttoken.StoredAgentTokenVerifier}
 * (해시 조회·폐기 확인·멤버십 확인·last_used_at 갱신, -175).
 */
public interface AgentTokenVerifier {

    /**
     * @param rawToken Authorization 헤더의 Bearer 값 (평문 — 로그에 남기지 않는다)
     * @throws AgentTokenException 무효·폐기·멤버십 종료
     */
    AgentPrincipal verify(String rawToken) throws AgentTokenException;
}
