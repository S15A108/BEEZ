package com.beez.backend.changeproposal.port;

import com.beez.backend.changeproposal.dto.PublicationDtos.*;
import com.beez.backend.common.auth.CallerContext;

/** #163 implements this boundary. apply must join the publication transaction; an exception rolls it all back. */
public interface ValidationImpactPort {
    ImpactResult applyPublication(CallerContext caller, long projectId, ImpactRequest request);
    /** No writes, locks or ID allocation; participates in the caller's consistent read snapshot. */
    ImpactResult previewPublication(long projectId, ImpactRequest request);
}
