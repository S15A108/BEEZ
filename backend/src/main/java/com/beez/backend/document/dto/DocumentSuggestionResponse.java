package com.beez.backend.document.dto;

public record DocumentSuggestionResponse(String id, String explanation, String originalText,
                                         String replacementText, String decision) { }
