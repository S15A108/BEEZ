package com.beez.backend.agenttoken;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGT-A01 ~ A03. 컨트롤러는 HTTP ↔ 서비스 변환만 한다. 규칙·권한은 전부 AgentTokenService 안에 있다.
 * Idempotency-Key 는 아직 처리하지 않는다 (저장 방식 Q12 미결, ProjectController 와 같음).
 */
@Tag(name = "agent-token", description = "에이전트 토큰 (AGT)")
@RestController
public class AgentTokenController {

    private final AgentTokenService tokens;

    public AgentTokenController(AgentTokenService tokens) {
        this.tokens = tokens;
    }

    /** 평문 토큰이 든 응답이라 어디에도 캐시되지 않게 no-store 를 직접 단다 (명세 응답 헤더). */
    @Operation(summary = "AGT-A01 에이전트 토큰 발급 (평문은 이 응답에서만)")
    @PostMapping("/api/projects/{id}/agent-tokens")
    public ResponseEntity<IssuedAgentTokenResponse> issue(CallerContext caller, @PathVariable("id") Long projectId,
                                                          @RequestBody(required = false) CreateAgentTokenRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(tokens.issue(caller, projectId, body));
    }

    @Operation(summary = "AGT-A02 내 에이전트 토큰 목록 (커서, 폐기 포함)")
    @GetMapping("/api/me/agent-tokens")
    public PageResponse<AgentTokenResponse> listMine(CallerContext caller,
                                                     @RequestParam(name = "project_id", required = false) String projectId,
                                                     @RequestParam(name = "cursor", required = false) String cursor,
                                                     @RequestParam(name = "limit", required = false) Integer limit) {
        return tokens.listMine(caller, projectId, cursor, limit);
    }

    @Operation(summary = "AGT-A03 에이전트 토큰 폐기 (본인 토큰)")
    @DeleteMapping("/api/agent-tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(CallerContext caller, @PathVariable("id") Long tokenId) {
        tokens.revoke(caller, tokenId);
    }
}
