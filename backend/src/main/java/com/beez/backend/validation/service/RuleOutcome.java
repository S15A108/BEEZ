package com.beez.backend.validation.service;

import com.beez.backend.validation.repository.FindingRow;
import java.util.List;
import java.util.Map;

/**
 * 규칙 검사 결과.
 *
 * @param decidedStatus 규칙만으로 확정된 상태(NEEDS_INPUT·NEEDS_REVIEW·PASS). 의미 분석이 필요하면 null
 * @param findings      규칙 발견 항목 (source=RULE)
 * @param checks        의미 분석에 넘길 검사 목록 (decidedStatus 가 null 일 때만 비어 있지 않다)
 */
public record RuleOutcome(String decidedStatus, List<FindingRow> findings, List<Map<String, Object>> checks) {
}
