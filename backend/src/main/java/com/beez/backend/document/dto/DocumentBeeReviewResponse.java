package com.beez.backend.document.dto;

import java.util.List;
import java.util.Map;

public record DocumentBeeReviewResponse(String id, long inputRevision, long resolvedRevision,
        String referenceHash, String summary, List<String> unavailableReferences,
        List<DocumentReviewReferenceResponse> references, List<DocumentSuggestionResponse> suggestions,
        List<Map<String, Object>> usage) { }
