package com.beez.backend.designchange.dto;

/**
 * MCP-03 get_design_changes 요청. 도구 어댑터(전진)가 인수를 이 형태로 넘긴다.
 *
 * @param sinceVersion 마지막으로 확인한 기준 번호. 필수, 최초 0
 * @param taskId       작업 범위 (선택, 문자열 ID)
 * @param cursor       이전 응답의 next_cursor (선택)
 * @param limit        페이지 크기 (선택, 기본 20, 최대 100). 영향 결과(impact_updates)에도 같은 크기를 쓴다
 * @param resultCursor 이전 응답의 next_result_cursor (선택, S15P31A108-221). 없으면 "지금부터" — 영향 결과 없이 시작점만 받는다
 */
public record DesignChangesQuery(Integer sinceVersion, String taskId, String cursor, Integer limit, String resultCursor) {
}
