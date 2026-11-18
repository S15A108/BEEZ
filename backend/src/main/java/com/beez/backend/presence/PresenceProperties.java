package com.beez.backend.presence;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 의 beez.presence.* — 팀원 접속 상태·위치 (MON-A06·A07, S15P31A108-386).
 *
 * @param ttl             마지막 보고가 이 안이면 online. FE 보고 주기(15초)의 3배라 보고 한두 번이 늦어도 끊기지 않는다
 * @param rateLimit       (계정, 프로젝트) 하나가 rateLimitWindow 안에 보낼 수 있는 보고 수. 탭을 여러 개 열어도 넘지 않는 값
 * @param rateLimitWindow 보고 한도를 세는 창
 * @param retention       마지막 보고 뒤 이 기간이 지난 항목을 메모리에서 뺀다. 오프라인 응답이 마지막 위치를 보여 주는 기간의 끝이다
 */
@Validated
@ConfigurationProperties(prefix = "beez.presence")
public record PresenceProperties(
        @NotNull @DurationMin(seconds = 1) Duration ttl,
        @Min(1) int rateLimit,
        @NotNull @DurationMin(seconds = 1) Duration rateLimitWindow,
        @NotNull @DurationMin(minutes = 1) Duration retention) {
}
