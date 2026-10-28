package com.beez.backend.auth.agent;

import com.beez.backend.common.access.AgentPrincipal;

/**
 * /mcp Bearer 토큰 검증. 본체(해시 조회·폐기 확인·멤버십 확인·last_used_at 갱신)는 -175/-176 에서 구현한다.
 * 그 전까지는 {@link RejectingAgentTokenVerifier} 가 모든 토큰을 거부한다.
 */
public interface AgentTokenVerifier {

    /**
     * @param rawToken Authorization 헤더의 Bearer 값 (평문 — 로그에 남기지 않는다)
     * @throws AgentTokenException 무효·폐기·멤버십 종료
     */
    AgentPrincipal verify(String rawToken) throws AgentTokenException;
}
