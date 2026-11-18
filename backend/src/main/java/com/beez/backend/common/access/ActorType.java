package com.beez.backend.common.access;

/** 요청 주체의 종류. HUMAN 은 /api/** 세션, AGENT 는 /mcp 프로젝트 토큰. */
public enum ActorType {
    HUMAN,
    AGENT
}
