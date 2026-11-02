package com.beez.backend.changeimpact.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.impactanalysis.ImpactRunService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/change-proposals/{proposalId}/impact")
public class ImpactController {
    private final ImpactRunService runs;
    private final DocumentRequestOriginVerifier origins;
    public ImpactController(ImpactRunService runs,DocumentRequestOriginVerifier origins) { this.runs=runs; this.origins=origins; }

    @GetMapping
    public ImpactRunService.Result get(CallerContext caller,@PathVariable long projectId,@PathVariable long proposalId) {
        return runs.get(caller,projectId,proposalId);
    }

    @PostMapping("/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public ImpactRunService.Result retry(CallerContext caller,HttpServletRequest request,
            @PathVariable long projectId,@PathVariable long proposalId,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        origins.requireTrustedOrigin(request);
        return runs.retry(caller,projectId,proposalId,key);
    }
}
