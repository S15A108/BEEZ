package com.beez.backend.validation.repository;

import java.util.List;
import java.util.Map;

/** validation_finding 한 줄. 저장 전에는 id 가 null */
public record FindingRow(Long id, Long validationId, Long validationRunId, String source, String kind, String severity,
                         Long designItemId, Long designItemVersionId, Long relatedTaskId, String message,
                         List<Map<String, Object>> sources, String resolution, String nextAction, String checkKey,
                         List<String> evidenceIds) {

    /** 규칙 검사가 만드는 발견 항목 (실행·검사 키 없음) */
    public static FindingRow rule(String kind, String severity, Long designItemId, Long designItemVersionId,
                                  Long relatedTaskId, String message, String resolution, String nextAction) {
        return new FindingRow(null, null, null, "RULE", kind, severity, designItemId, designItemVersionId, relatedTaskId,
                message, List.of(), resolution, nextAction, null, List.of());
    }

    /** BE 가 만드는 실행 오류 항목 (source=MODEL, kind=ERROR, BLOCKING, 실행 필수 — 공통 "검증 실행 오류 규약") */
    public static FindingRow error(Long runId, String message, String resolution, String nextAction) {
        return new FindingRow(null, null, runId, "MODEL", "ERROR", "BLOCKING", null, null, null, message, List.of(),
                resolution, nextAction, null, List.of());
    }
}
