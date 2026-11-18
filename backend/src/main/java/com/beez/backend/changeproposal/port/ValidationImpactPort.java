package com.beez.backend.changeproposal.port;

import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.common.auth.CallerContext;

/** #163 implements this boundary. apply must join the publication transaction; an exception rolls it all back. */
public interface ValidationImpactPort {
    /**
     * Called before project.current_baseline_id is replaced with the new baseline. Select affected versions
     * from request.previousBaselineId() and request.baselineId(), not the project's current pointer.
     * changedItems contains UPDATE and RETIRE only. CREATE is excluded because existing plans cannot reference
     * an item that has not existed before this publication. Any failure rolls back the publication transaction.
     */
    ImpactResult applyPublication(CallerContext caller, long projectId, ImpactRequest request);
    /** No writes, locks or ID allocation; participates in the caller's consistent read snapshot. */
    ImpactResult previewPublication(long projectId, ImpactRequest request);
}
