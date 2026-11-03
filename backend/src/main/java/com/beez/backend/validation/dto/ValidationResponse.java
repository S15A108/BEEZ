package com.beez.backend.validation.dto;

import java.time.Instant;
import java.util.List;

/**
 * 명세 00-common Validation (VAL-A01~A03, MCP-11 응답).
 * error_code 는 ERROR 일 때만 — 현재 실패 실행에서 조립한 값이고 DB 칸이 아니다 (공통 "검증 실행 오류 규약").
 */
public record ValidationResponse(String id, String taskId, String planRevisionId, String baselineId, String status,
                                 String trigger, String staleReason, int currentAttempt,
                                 List<ValidationDesignRefResponse> designRefs,
                                 List<RelatedTaskRefResponse> relatedTaskRefs, List<FindingResponse> findings,
                                 boolean cacheHit, Instant createdAt, Instant finishedAt, String errorCode) {
}
