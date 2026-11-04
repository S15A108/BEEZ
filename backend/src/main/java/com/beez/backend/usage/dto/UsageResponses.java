package com.beez.backend.usage.dto;

import java.time.Instant;
import java.util.List;

/** AI 사용량·추정 비용 응답 (S15P31A108-169, MON-A02). 명세 00-common 의 UsageSummary·UsageBucket */
public final class UsageResponses {

    private UsageResponses() {
    }

    /**
     * 명세 UsageBucket. 값이 없는 선택 필드(label·estimated_cost_amount)는 응답에서 빠진다(전역 non_null).
     * tokens_unknown_call_count·cost_unknown_call_count 는 BE 추가 — 토큰·비용 합계에 들어가지 못한 호출 수 (0 이면 합계가 전부를 셌다)
     */
    public record UsageBucket(String key, String label, long inputTokens, long outputTokens, long callCount,
                              String estimatedCostAmount, long tokensUnknownCallCount, long costUnknownCallCount) {
    }

    /** 명세 UsageSummary (+ 위 두 개수) */
    public record UsageSummary(Instant from, Instant to, long inputTokens, long outputTokens, long callCount,
                               String estimatedCostAmount, String currency, long tokensUnknownCallCount, long costUnknownCallCount,
                               List<UsageBucket> byFeature, List<UsageBucket> byTask, List<UsageBucket> byValidation,
                               List<UsageBucket> byAgent, List<UsageBucket> byDay) {
    }
}
