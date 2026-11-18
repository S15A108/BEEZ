package com.beez.backend.validation.repository;

import java.time.Instant;

/** validation 한 줄 (프로젝트 번호는 task 조인으로 같이 읽는다) */
public record ValidationRow(Long id, Long projectId, Long taskId, Long planRevisionId, Long baselineId, String status,
                            String trigger, String staleReason, int currentAttempt, String inputSnapshotHash,
                            String summary, Instant requestedAt, Instant finishedAt, int lockVersion) {
}
