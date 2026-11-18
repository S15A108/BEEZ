package com.beez.backend.agenttoken;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 의 beez.agent.* (AGT-A01). 기동 시 검증되어 빠진 값이 있으면 바로 실패한다.
 *
 * @param mcpUrl          발급 응답의 연결 URL. Claude Code 가 붙는 backend 의 공개 /mcp 주소
 * @param maxActiveTokens 프로젝트당 한 계정의 폐기 안 된 토큰 수 상한 (Q06 — 여러 PC 와 응답 유실 후 재발급을 덮는 값)
 */
@Validated
@ConfigurationProperties(prefix = "beez.agent")
public record AgentTokenProperties(@NotBlank String mcpUrl, @Min(1) int maxActiveTokens) {
}
