package com.beez.backend.document.dto;

import java.util.List;

public record DocumentWorkingCopyResponse(String documentId, String ownerAccountId, long revision,
        String status, String content, String fileName, DocumentBeeReviewResponse latestReview,
        List<DocumentSubmissionResponse> submissions) { }
