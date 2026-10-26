package com.beez.backend.task.entity;

/**
 * WAITING 사유. REVALIDATION 은 시스템만 설정한다 — 설계 발행이나 계획 변경으로 재검증이 필요할 때.
 * 사람·에이전트가 요청에 REVALIDATION 을 넣으면 400 invalid_transition.
 */
public enum WaitingReason {
    INPUT,
    APPROVAL,
    DEPENDENCY,
    REVALIDATION;

    public boolean isSystemOnly() {
        return this == REVALIDATION;
    }
}
