package com.beez.backend.auth.recovery;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 의 beez.password-recovery.* (ACC-A07). 모두 Q04 가 정해지기 전 기본안이다 — 한도는 가입 인증(-219)과 같은 값으로 시작한다.
 *
 * @param ttl             임시 비밀번호 유효 시간. 지나면 로그인에 쓸 수 없다
 * @param resendInterval  같은 이메일로 다시 요청할 수 있는 간격
 * @param sendLimit       한도 창 안에서 한 이메일로 요청할 수 있는 횟수 (계정 유무와 관계없이 센다)
 * @param sendLimitWindow 발송 한도를 세는 창 (이메일당·서버 전체 공통)
 * @param globalSendLimit 한도 창 안에서 서버 전체가 보낼 수 있는 메일 수. SMTP 계정 한도보다 낮게 둔다
 */
@Validated
@ConfigurationProperties(prefix = "beez.password-recovery")
public record PasswordRecoveryProperties(
        @NotNull @DurationMin(seconds = 1) Duration ttl,
        @NotNull Duration resendInterval,
        @Min(1) int sendLimit,
        @NotNull @DurationMin(seconds = 1) Duration sendLimitWindow,
        @Min(1) int globalSendLimit) {
}
