package com.beez.backend.changeimpact.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.impactanalysis.ImpactRunService;
import com.beez.backend.impactanalysis.OwnerRecommendationDtos;
import com.beez.backend.impactanalysis.OwnerRecommendationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/change-proposals/{proposalId}/impact")
public class ImpactController {
    private final ImpactRunService runs;
    private final DocumentRequestOriginVerifier origins;
    private final OwnerRecommendationService owners;
    public ImpactController(ImpactRunService runs,DocumentRequestOriginVerifier origins,OwnerRecommendationService owners) {
        this.runs=runs; this.origins=origins; this.owners=owners;
    }

    public record Response(List<?> explicit, List<?> candidates, String analysisStatus, Object generation,
                           String errorCode, int revision, String snapshotHash, boolean isCurrent, String staleReason,
                           String runId, ImpactRunService.Reanalysis reanalysis,
                           OwnerRecommendationDtos.Result ownerRecommendations) {}

    @GetMapping
    public Response get(CallerContext caller,@PathVariable long projectId,@PathVariable long proposalId) {
        // Keep the existing lease-expiry cleanup, then assemble impact and recommendations from
        // one read-only snapshot so their revision and freshness cannot be mixed.
        runs.expireForRead(caller,projectId,proposalId);
        var review=owners.forReview(caller,projectId,proposalId);
        var value=review.impact();
        return new Response(value.explicit(),value.candidates(),value.analysisStatus(),value.generation(),
                value.errorCode(),value.revision(),value.snapshotHash(),value.isCurrent(),value.staleReason(),
                value.runId(),value.reanalysis(),review.owners());
    }

    @PostMapping("/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public ImpactRunService.Result retry(CallerContext caller,HttpServletRequest request,
            @PathVariable long projectId,@PathVariable long proposalId,@RequestHeader(value="Idempotency-Key",required=false) String key) {
        origins.requireTrustedOrigin(request);
        return runs.retry(caller,projectId,proposalId,key);
    }
}
