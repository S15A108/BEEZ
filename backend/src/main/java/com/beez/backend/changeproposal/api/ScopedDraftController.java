package com.beez.backend.changeproposal.api;

import com.beez.backend.changeproposal.service.ScopedDraftService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{projectId}/change-drafts/scoped")
public class ScopedDraftController {
    private final ScopedDraftService drafts;
    private final DocumentRequestOriginVerifier origins;
    public ScopedDraftController(ScopedDraftService drafts, DocumentRequestOriginVerifier origins) {
        this.drafts = drafts; this.origins = origins;
    }
    @PostMapping("/blocks")
    public Map<String, Object> blocks(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
                                      @RequestBody ScopedDraftService.BlockRequest input) {
        origins.requireTrustedOrigin(request);
        return drafts.blocks(caller, projectId, input);
    }
    @PostMapping
    public Map<String, Object> generate(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @RequestBody ScopedDraftService.ScopedRequest input, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return drafts.generate(caller, projectId, input, key);
    }
}
