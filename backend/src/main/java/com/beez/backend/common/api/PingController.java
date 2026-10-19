package com.beez.backend.common.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 기동 확인용. 인증 없이 열려 있는 유일한 비즈니스 경로로 둔다. */
@Tag(name = "system")
@RestController
public class PingController {

    private static final Instant STARTED_AT = Instant.now();

    public record Pong(boolean ok, String service, Instant startedAt, Instant serverTime) {
    }

    @Operation(summary = "기동 확인 (응답은 snake_case: started_at, server_time)")
    @GetMapping("/api/ping")
    public Pong ping() {
        return new Pong(true, "beez-backend", STARTED_AT, Instant.now());
    }
}
