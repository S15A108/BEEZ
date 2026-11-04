package com.beez.backend.validation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 검증 워커 설정 (application.yml beez.validation).
 *
 * @param workerEnabled     false 면 주기 실행을 멈춘다 (테스트는 runOnce 를 직접 부른다)
 * @param pollMillis        큐를 보는 간격
 * @param leaseSeconds      한 번 꺼낸 작업을 쥐고 있는 시간. AI 읽기 시간 초과보다 길어야 한다
 * @param executionBudgetMs AI 에게 주는 처리 예산 (AI-A02 execution_budget_ms). 읽기 시간 초과보다 짧아야 한다
 * @param maxModelAttempts  같은 작업의 같은 검증 입력(입력 스냅샷 해시)으로 분석을 돌릴 수 있는 횟수. 새 검증 요청과 재시도(VAL-A04)를
 *                          합쳐 센다. 서버 쪽 중단(리스 만료·워커 중단)으로 닫힌 실행은 세지 않는다 (S15P31A108-161)
 */
@ConfigurationProperties("beez.validation")
public record ValidationProperties(boolean workerEnabled, long pollMillis, int leaseSeconds, int executionBudgetMs,
                                   int maxModelAttempts) {
    public ValidationProperties {
        if (pollMillis < 100 || leaseSeconds < 5 || executionBudgetMs < 1000 || maxModelAttempts < 1) {
            throw new IllegalArgumentException(
                    "beez.validation: poll-millis ≥ 100, lease-seconds ≥ 5, execution-budget-ms ≥ 1000, max-model-attempts ≥ 1");
        }
    }
}
