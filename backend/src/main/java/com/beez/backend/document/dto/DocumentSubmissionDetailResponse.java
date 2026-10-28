package com.beez.backend.document.dto;

public record DocumentSubmissionDetailResponse(DocumentSubmissionResponse submission, String content,
                                               DocumentBeeReviewResponse review) { }
