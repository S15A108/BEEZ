package com.beez.backend.auth.agent;

import com.beez.backend.common.access.AgentPrincipal;

/** 토큰 발급(-175) 전의 자리. 모든 토큰을 401 로 거부한다. -175 에서 실제 구현으로 교체하고 이 클래스는 지운다. */
public class RejectingAgentTokenVerifier implements AgentTokenVerifier {

    @Override
    public AgentPrincipal verify(String rawToken) {
        throw new AgentTokenException("unauthenticated", "agent token verification is not implemented yet");
    }
}
