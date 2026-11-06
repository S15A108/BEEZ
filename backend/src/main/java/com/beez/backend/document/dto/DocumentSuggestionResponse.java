package com.beez.backend.document.dto;

import java.util.List;

public record DocumentSuggestionResponse(String id, String explanation, String originalText,
        String replacementText, String decision, String type, String step,
        List<DocumentReviewSourceResponse> sources, String origin, String appliedReplacementText,
        List<DocumentMessageResponse> messages) { }
