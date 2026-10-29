package com.beez.backend.designitem.dto;

import java.util.List;

public record InitialBaselineResponse(
        String id,
        String projectId,
        int versionNo,
        List<BaselineDocumentRefResponse> documents,
        List<BaselineItemRefResponse> items,
        String approvedBy,
        String createdAt
) {
}
