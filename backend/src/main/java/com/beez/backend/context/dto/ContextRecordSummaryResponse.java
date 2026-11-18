package com.beez.backend.context.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.context.entity.ContextRecord;
import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
import java.time.Instant;

/** 명세 00-common ContextRecordSummary (MCP-05·CTX-A02 목록 항목) */
public record ContextRecordSummaryResponse(
        String id,
        String rootId,
        Integer revisionNo,
        String taskId,
        ContextRecordKind kind,
        String summary,
        String authorAccountId,
        Visibility visibility,
        boolean isLatestRevision,
        boolean referencesCurrent,
        Instant createdAt
) {
    public static ContextRecordSummaryResponse of(ContextRecord r, boolean latest, boolean current) {
        return new ContextRecordSummaryResponse(Ids.str(r.getId()), Ids.str(r.rootIdForApi()), r.getRevisionNo(),
                Ids.str(r.getTaskId()), r.getKind(), r.getSummary(), Ids.str(r.getAuthorAccountId()), r.getVisibility(),
                latest, current, r.getCreatedAt());
    }
}
