package com.beez.backend.changeproposal.api;

import com.beez.backend.changeproposal.dto.DraftDtos.Draft;
import com.beez.backend.changeproposal.dto.DraftDtos.Request;
import com.beez.backend.changeproposal.service.ChangeDraftService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/change-drafts")
@Tag(name = "Change drafts", description = "문서 수정 초안 생성 — 읽기·생성 전용이며 문서·기준·제안을 바꾸지 않는다")
public class ChangeDraftController {
    private final ChangeDraftService drafts;
    private final DocumentRequestOriginVerifier origins;

    public ChangeDraftController(ChangeDraftService drafts, DocumentRequestOriginVerifier origins) {
        this.drafts = drafts; this.origins = origins;
    }

    @PostMapping
    @Operation(summary = "CHG-A07 문서 수정 초안 생성")
    public Draft generate(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
                          @RequestBody Request input, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return drafts.generate(caller, projectId, input, key);
    }
}
