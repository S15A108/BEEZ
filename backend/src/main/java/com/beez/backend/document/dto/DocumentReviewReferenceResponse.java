package com.beez.backend.document.dto;

public record DocumentReviewReferenceResponse(String documentId, String documentVersionId, String kind,
                                              int versionNo, String content) { }
