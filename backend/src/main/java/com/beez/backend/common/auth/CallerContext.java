package com.beez.backend.common.auth;

/**
 * "지금 이 요청을 보낸 사람이 누구인가"를 담는 값.
 *
 * <p>서비스 메서드는 이 값을 인자로 받는다. REST 는 {@link CallerResolver} 가 만들어 넘기고,
 * MCP 어댑터(전진)는 에이전트 토큰에서 직접 만들어 넘긴다. 인증 방식이 둘이어도 업무 로직은 하나다.
 *
 * @param accountId      계정 ID. 에이전트 토큰이면 토큰 소유 계정
 * @param agentTokenId   에이전트 토큰 ID. 사람 세션이면 null
 * @param kind           HUMAN 또는 AGENT
 * @param agentSessionId 이 요청이 들어온 MCP 연결의 기록 ID(agent_session.id, -333). 활동의 agent_session_id 가 된다.
 *                       MCP 도구 호출만 채운다 — 사람 요청·검증 워커가 다시 만든 호출자는 null (에이전트 토큰은 /mcp 로만 인증된다)
 */
public record CallerContext(Long accountId, Long agentTokenId, CallerKind kind, Long agentSessionId) {

    public CallerContext {
        if (kind == CallerKind.HUMAN && agentSessionId != null) {
            throw new IllegalArgumentException("human caller must not carry agentSessionId");
        }
    }

    /** 연결 기록 없는 호출자 (-333 이전 형태). */
    public CallerContext(Long accountId, Long agentTokenId, CallerKind kind) {
        this(accountId, agentTokenId, kind, null);
    }

    public static CallerContext human(Long accountId) {
        return new CallerContext(accountId, null, CallerKind.HUMAN);
    }

    public static CallerContext agent(Long accountId, Long agentTokenId) {
        return new CallerContext(accountId, agentTokenId, CallerKind.AGENT);
    }

    /** MCP 도구 호출. 토큰 주인이 아니라 그 요청의 연결(Mcp-Session-Id 로 확인한 기록)까지 잇는다 */
    public static CallerContext agent(Long accountId, Long agentTokenId, Long agentSessionId) {
        return new CallerContext(accountId, agentTokenId, CallerKind.AGENT, agentSessionId);
    }

    public boolean isHuman() {
        return kind == CallerKind.HUMAN;
    }
}
