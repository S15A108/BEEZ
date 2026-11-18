package com.beez.backend.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/** /swagger-ui.html · /v3/api-docs. 운영에서는 SWAGGER_ENABLED=true 일 때만 열린다. */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "BEEZ API",
                version = "v0",
                description = "프로젝트를 기억하는 AI 팀장 — 사람이 확정하고, 실행 증거로 판정한다."),
        servers = @Server(url = "/", description = "현재 서버"))
public class OpenApiConfig {
}
