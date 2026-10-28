package com.beez.backend.document.dto;

import jakarta.validation.constraints.Positive;

public record DocumentReviewRequest(@Positive long expectedRevision) { }
