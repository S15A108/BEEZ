package com.beez.backend.planning.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.planning.dto.DraftDtos.AdoptRequest;
import com.beez.backend.planning.dto.DraftDtos.CreateRequest;
import com.beez.backend.planning.service.DesignDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Design drafts", description = "답변 반영 설계 초안 — 생성 후보로 보존하고 팀장이 채택할 때만 설계 항목 DRAFT 가 된다")
public class DesignDraftController {
    private final DesignDraftService drafts;
    private final DocumentRequestOriginVerifier origins;

    public DesignDraftController(DesignDraftService drafts, DocumentRequestOriginVerifier origins) {
        this.drafts = drafts; this.origins = origins;
    }

    @PostMapping("/design-drafts")
    @Operation(summary = "PLN-A05 답변을 반영한 설계 초안 생성")
    public ResponseEntity<Map<String, Object>> create(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @RequestBody CreateRequest body, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return ResponseEntity.accepted().body(drafts.create(caller, projectId, body, key));
    }

    @GetMapping("/design-drafts")
    @Operation(summary = "설계 초안 생성 작업과 생성 후보 목록")
    public Map<String, Object> list(CallerContext caller, @PathVariable long projectId,
            @RequestParam(name = "document_version_id", required = false) Long documentVersionId) {
        return drafts.list(caller, projectId, documentVersionId);
    }

    @PostMapping("/design-draft-candidates/{candidateId}/adopt")
    @Operation(summary = "생성 후보 채택 — 설계 항목 DRAFT 로 반영")
    public Map<String, Object> adopt(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long candidateId, @RequestBody(required = false) AdoptRequest body) {
        origins.requireTrustedOrigin(request);
        return drafts.adopt(caller, projectId, candidateId, body);
    }

    @PostMapping("/design-draft-candidates/{candidateId}/discard")
    @Operation(summary = "생성 후보 폐기 — 설계 항목에는 영향 없음")
    public Map<String, Object> discard(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long candidateId) {
        origins.requireTrustedOrigin(request);
        return drafts.discard(caller, projectId, candidateId);
    }
}
