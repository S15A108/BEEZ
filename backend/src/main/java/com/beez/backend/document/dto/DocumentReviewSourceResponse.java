package com.beez.backend.document.dto;

public record DocumentReviewSourceResponse(String referenceId, String documentId, String documentVersionId,
        String kind, int versionNo, String quote) { }
