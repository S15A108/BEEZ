package com.beez.backend.impactanalysis;

import com.beez.backend.common.auth.CallerContext;
import org.springframework.stereotype.Service;

/** Freshness information for #241; this query never makes an approval decision. */
@Service
public class ImpactStatusQuery {
    private final ImpactRunService runs;
    public ImpactStatusQuery(ImpactRunService runs) { this.runs=runs; }
    public record Status(String status, boolean isCurrent, int revision, String errorCode, int candidateCount) {}
    /** Joins the caller's transaction; also closes an expired RUNNING lease, so it needs a writable one. */
    public Status currentFor(CallerContext caller,long project,long proposal) {
        return status(runs.get(caller,project,proposal));
    }
    /** For read-only transactions: an expired RUNNING lease is reported as RUNNING until the next writable read. */
    public Status peekFor(CallerContext caller,long project,long proposal) {
        return status(runs.peek(caller,project,proposal));
    }
    private static Status status(ImpactRunService.Result result) {
        return new Status(result.analysisStatus(),result.isCurrent(),result.revision(),result.errorCode(),result.candidates().size());
    }
}
