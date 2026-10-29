package com.beez.backend.context.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.context.entity.ContextRecord;
import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
import java.time.Instant;
import java.util.List;

/** 명세 00-common ContextRecord (MCP-06 상세·MCP-07 응답·CTX-A03·A04) */
public record ContextRecordResponse(
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
        Instant createdAt,
        String previousId,
        String planRevisionId,
        String sourcePacketId,
        String detail,
        List<VersionRefDto> designRefs
) {
    public static ContextRecordResponse of(ContextRecord r, boolean latest, boolean current, List<VersionRefDto> refs) {
        return new ContextRecordResponse(Ids.str(r.getId()), Ids.str(r.rootIdForApi()), r.getRevisionNo(),
                Ids.str(r.getTaskId()), r.getKind(), r.getSummary(), Ids.str(r.getAuthorAccountId()), r.getVisibility(),
                latest, current, r.getCreatedAt(), Ids.str(r.getPreviousId()), Ids.str(r.getPlanRevisionId()),
                Ids.str(r.getSourcePacketId()), r.getDetail(), refs);
    }
}
