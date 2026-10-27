package com.beez.backend.common.auth;

/**
 * "지금 이 요청을 보낸 사람이 누구인가"를 담는 값.
 *
 * <p>서비스 메서드는 이 값을 인자로 받는다. REST 는 {@link CallerResolver} 가 만들어 넘기고,
 * MCP 어댑터(전진)는 에이전트 토큰에서 직접 만들어 넘긴다. 인증 방식이 둘이어도 업무 로직은 하나다.
 *
 * @param accountId     계정 ID. 에이전트 토큰이면 토큰 소유 계정
 * @param agentTokenId  에이전트 토큰 ID. 사람 세션이면 null
 * @param kind          HUMAN 또는 AGENT
 */
public record CallerContext(Long accountId, Long agentTokenId, CallerKind kind) {

    public static CallerContext human(Long accountId) {
        return new CallerContext(accountId, null, CallerKind.HUMAN);
    }

    public static CallerContext agent(Long accountId, Long agentTokenId) {
        return new CallerContext(accountId, agentTokenId, CallerKind.AGENT);
    }

    public boolean isHuman() {
        return kind == CallerKind.HUMAN;
    }
}
