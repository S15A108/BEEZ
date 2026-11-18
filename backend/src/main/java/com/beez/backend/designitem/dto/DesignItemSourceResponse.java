package com.beez.backend.designitem.dto;

public record DesignItemSourceResponse(
        String documentId,
        String documentVersionId,
        int versionNo,
        String location
) {
}
