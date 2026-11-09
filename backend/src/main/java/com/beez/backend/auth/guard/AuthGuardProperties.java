package com.beez.backend.auth.guard;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 의 beez.auth-guard.* — 가입 인증·비밀번호 찾기 남용 대책 (Q04 결정 2026-11-09, S15P31A108-273).
 * 행 정리 시각(row-cleanup-cron)은 {@code @Scheduled} 가 직접 읽는다.
 *
 * @param ipLimit       한도 창 안에서 한 IP 가 ACC-A11·ACC-A07 을 합쳐 부를 수 있는 횟수 (Q04: 10)
 * @param ipLimitWindow IP 한도를 세는 창 (Q04: 1시간)
 * @param rowRetention  끝난 email_verification·temporary_password 행을 남겨 두는 기간 (Q04: 7일).
 *                      이메일당·서버 전체 한도가 최근 1시간 행을 세므로 하루보다 짧게 두지 못하게 막는다
 */
@Validated
@ConfigurationProperties(prefix = "beez.auth-guard")
public record AuthGuardProperties(
        @Min(1) int ipLimit,
        @NotNull @DurationMin(seconds = 1) Duration ipLimitWindow,
        @NotNull @DurationMin(days = 1) Duration rowRetention) {
}
