package com.beez.backend.auth.verification;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 의 beez.email-verification.* (ACC-A11·A12). 코드 유효 5분만 명세 값이고 나머지는 Q04 가 정해지기 전 기본안이다.
 *
 * @param codeTtl         코드 유효 시간 (명세 기본안 5분)
 * @param resendInterval  다시 받기 간격 (명세 ACC-A11 응답 예시의 1분)
 * @param sendLimit       한도 창 안에서 한 이메일로 보낼 수 있는 횟수
 * @param sendLimitWindow 발송 한도를 세는 창 (이메일당·서버 전체 공통)
 * @param globalSendLimit 한도 창 안에서 서버 전체가 보낼 수 있는 횟수. SMTP 계정의 발송 한도보다 낮게 둔다
 * @param maxAttempts     한 코드에 틀릴 수 있는 횟수. 이만큼 틀리면 429 too_many_attempts (FE 목과 같은 5회)
 * @param tokenTtl        verified_token 유효 시간 (명세 ACC-A12 응답 예시의 30분)
 */
@Validated
@ConfigurationProperties(prefix = "beez.email-verification")
public record EmailVerificationProperties(
        @NotNull @DurationMin(seconds = 1) Duration codeTtl,
        @NotNull Duration resendInterval,
        @Min(1) int sendLimit,
        @NotNull @DurationMin(seconds = 1) Duration sendLimitWindow,
        @Min(1) int globalSendLimit,
        @Min(1) int maxAttempts,
        @NotNull @DurationMin(seconds = 1) Duration tokenTtl) {
}
