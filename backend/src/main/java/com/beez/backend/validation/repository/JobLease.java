package com.beez.backend.validation.repository;

import java.util.UUID;

/** 워커가 꺼낸 작업 하나 */
public record JobLease(Long jobId, Long validationId, int attempt, UUID leaseToken) {
}
