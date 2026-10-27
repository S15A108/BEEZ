package com.beez.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** application.yml 의 beez.* 설정. 기동 시 검증되어 빠진 값이 있으면 바로 실패한다. */
@Validated
@ConfigurationProperties(prefix = "beez")
public record BeezProperties(@Valid @NotNull Cors cors, @Valid @NotNull Ai ai, @Valid @NotNull Invitation invitation) {

    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }

    /** FastAPI AI 서버 연결 정보. backend ↔ ai 는 internalToken 으로 상호 인증한다. */
    public record Ai(
            @NotBlank String baseUrl,
            @NotBlank String internalToken,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }

    /**
     * 팀원 초대 링크 (MEM-A01). 링크는 프론트 화면 주소라 baseUrl 은 프론트 오리진이다.
     * 만료 7일·1회 사용은 Q05 제안값이다.
     */
    public record Invitation(@NotBlank String baseUrl, @NotNull Duration ttl) {
    }
}
