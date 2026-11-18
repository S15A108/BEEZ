package com.beez.backend.document.dto;

public record DocumentSubmissionResponse(String id, String documentId, String documentVersionId,
        String submittedBy, long revision, String status, String contentHash,
        String submittedAt, String decidedBy, String decidedAt, String rejectionReason) { }
