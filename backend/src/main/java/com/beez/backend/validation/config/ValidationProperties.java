package com.beez.backend.validation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검증 워커 설정 (application.yml beez.validation).
 *
 * @param workerEnabled     false 면 주기 실행을 멈춘다 (테스트는 runOnce 를 직접 부른다)
 * @param pollMillis        큐를 보는 간격
 * @param leaseSeconds      한 번 꺼낸 작업을 쥐고 있는 시간. AI 읽기 시간 초과보다 길어야 한다
 * @param executionBudgetMs AI 에게 주는 처리 예산 (AI-A02 execution_budget_ms). 읽기 시간 초과보다 짧아야 한다
 */
@ConfigurationProperties("beez.validation")
public record ValidationProperties(boolean workerEnabled, long pollMillis, int leaseSeconds, int executionBudgetMs) {
    public ValidationProperties {
        if (pollMillis < 100 || leaseSeconds < 5 || executionBudgetMs < 1000) {
            throw new IllegalArgumentException("beez.validation: poll-millis ≥ 100, lease-seconds ≥ 5, execution-budget-ms ≥ 1000");
        }
    }
}
