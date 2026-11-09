package com.beez.backend.common.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 의 beez.mail.* (S15P31A108-219). SMTP 접속 값은 Spring Boot 표준 spring.mail.* 이 갖는다.
 *
 * @param mode    smtp 면 실제로 보내고, log 면 로그에만 남긴다 (로컬·테스트)
 * @param from    보내는 사람 주소
 * @param logBody log 구현이 본문(인증 코드 포함)까지 찍을지. 로컬에서 코드를 받을 방법이 로그뿐이라 local 프로필만 켠다
 * @param shutdownWait 종료가 시작된 때(SIGTERM)부터 비동기로 보내던 메일을 기다리는 한계 ({@link AsyncMailSender}, -334).
 *                     웹 서버가 요청을 기다린 시간도 여기 든다. compose stop_grace_period(30초)보다 짧게 둔다
 */
@Validated
@ConfigurationProperties(prefix = "beez.mail")
public record BeezMailProperties(@NotNull Mode mode, @NotBlank String from, boolean logBody,
                                 @NotNull Duration shutdownWait) {

    public enum Mode {
        SMTP, LOG
    }
}
