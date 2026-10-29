package com.beez.backend.designitem.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignItemPageResponse(
        List<DesignItemResponse> items,
        boolean hasMore,
        String nextCursor,
        String draftSnapshotHash
) {
}
