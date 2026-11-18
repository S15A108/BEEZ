package com.beez.backend.designchange.dto;

import java.time.Instant;

/** 명세 00-common BaselineSummary. created_at 은 발행 시각(baseline.published_at) */
public record BaselineSummaryResponse(String id, Integer versionNo, Instant createdAt) {
}
