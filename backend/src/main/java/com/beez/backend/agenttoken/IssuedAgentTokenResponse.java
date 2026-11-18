package com.beez.backend.agenttoken;

/** AGT-A01 응답. token 평문은 여기서만 준다 — 목록·재조회·로그에는 없다. */
public record IssuedAgentTokenResponse(AgentTokenResponse tokenInfo, String token, String mcpUrl,
                                       String claudeMdSnippet) {
}
