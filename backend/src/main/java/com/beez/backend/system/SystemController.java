package com.beez.backend.system;

import com.beez.backend.ai.AiClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "system", description = "기동·연결 상태")
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final AiClient ai;

    public SystemController(AiClient ai) {
        this.ai = ai;
    }

    public record Status(boolean ok, String service, boolean aiReachable, Instant time) {
    }

    @Operation(summary = "backend 와 ai 서버 연결 상태 (ai 가 꺼져 있어도 200)")
    @GetMapping("/status")
    public Status status() {
        return new Status(true, "beez-backend", ai.ping(), Instant.now());
    }
}
