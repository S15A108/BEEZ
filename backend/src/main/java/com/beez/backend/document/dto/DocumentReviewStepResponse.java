package com.beez.backend.document.dto;

import java.util.List;

public record DocumentReviewStepResponse(String step, String status, String reason, List<String> referenceIds) { }
