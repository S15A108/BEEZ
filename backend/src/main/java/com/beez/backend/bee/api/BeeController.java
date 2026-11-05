package com.beez.backend.bee.api;

import com.beez.backend.bee.dto.BeeFeedbackRequest;
import com.beez.backend.bee.dto.BeeStatusResponse;
import com.beez.backend.bee.service.BeeService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * BEE-A03·A04 (S15P31A108-234). 쓰기 요청은 Origin 을 먼저 보고 본문 검증은 서비스에서 한다.
 * Idempotency-Key 는 받지 않는다 — (answer, 계정) 단위 덮어쓰기라 재시도가 자연히 멱등이다.
 */
@Tag(name = "bee", description = "비비 답변 피드백·현재 상태 (BEE-A03·A04)")
@RestController
@RequestMapping("/api/projects/{projectId}/bee")
public class BeeController {

    private final BeeService bee;
    private final DocumentRequestOriginVerifier origins;

    public BeeController(BeeService bee, DocumentRequestOriginVerifier origins) {
        this.bee = bee;
        this.origins = origins;
    }

    @Operation(summary = "비비 답변 피드백 (BEE-A03). 본인이 받은 답변만, 다시 보내면 덮어씀")
    @PostMapping("/answers/{answerId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(CallerContext caller, HttpServletRequest request, @PathVariable("projectId") Long projectId,
                         @PathVariable("answerId") String answerId, @RequestBody(required = false) BeeFeedbackRequest body) {
        origins.requireTrustedOrigin(request);
        bee.feedback(caller, projectId, answerId, body);
    }

    @Operation(summary = "비비 현재 상태 (BEE-A04). 진행 중인 문서 검토·열린 회의 중 가장 최근 하나")
    @GetMapping("/status")
    public BeeStatusResponse status(CallerContext caller, @PathVariable("projectId") Long projectId) {
        return bee.status(caller, projectId);
    }
}
