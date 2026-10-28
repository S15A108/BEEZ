package com.beez.backend.document.dto;

public record DocumentReviewNotificationResponse(String id, String documentId, String submissionId,
                                                 String status, String rejectionReason, String createdAt) { }
