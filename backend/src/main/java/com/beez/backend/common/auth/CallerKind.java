package com.beez.backend.common.auth;

/** 요청을 보낸 주체의 종류. 사람 세션인지, 에이전트 토큰인지. 승인·취소 같은 일부 동작은 HUMAN 만 할 수 있다. */
public enum CallerKind {
    HUMAN,
    AGENT
}
