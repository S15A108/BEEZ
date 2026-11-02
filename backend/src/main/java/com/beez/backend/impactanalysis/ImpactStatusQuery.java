package com.beez.backend.impactanalysis;

import com.beez.backend.common.auth.CallerContext;
import org.springframework.stereotype.Service;

/** Freshness information for #241; this query never makes an approval decision. */
@Service
public class ImpactStatusQuery {
    private final ImpactRunService runs;
    public ImpactStatusQuery(ImpactRunService runs) { this.runs=runs; }
    public record Status(String status, boolean isCurrent, int revision, String errorCode) {}
    public Status currentFor(CallerContext caller,long project,long proposal) {
        var result=runs.get(caller,project,proposal);
        return new Status(result.analysisStatus(),result.isCurrent(),result.revision(),result.errorCode());
    }
}
