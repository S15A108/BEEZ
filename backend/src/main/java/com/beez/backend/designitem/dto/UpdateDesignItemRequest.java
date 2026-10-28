package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpdateDesignItemRequest(
        @NotBlank String projectId,
        @NotBlank String expectedVersionId,
        @NotNull Map<String, Object> content,
        @NotBlank String expectedContentHash,
        Boolean replaceSource,
        String sourceDocumentVersionId,
        @jakarta.validation.constraints.Size(max = 10000) String sourceLocation,
        String expectedDraftSnapshotHash
) {
    public UpdateDesignItemRequest(String projectId, String expectedVersionId, Map<String, Object> content, String expectedContentHash) {
        this(projectId, expectedVersionId, content, expectedContentHash, null, null, null, null);
    }
}
