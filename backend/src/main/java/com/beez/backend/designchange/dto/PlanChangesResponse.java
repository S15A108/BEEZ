package com.beez.backend.designchange.dto;

/**
 * report_task_state(MCP-12)용 변경 요약과, 그 요약을 계산한 기준 번호 (S15P31A108-166).
 * changes.has_more 이면 get_design_changes(since_version = sinceVersion, task_id = 같은 작업, cursor = changes.next_cursor)로 이어 본다.
 *
 * @param sinceVersion 그 작업 현재 계획을 세운 시점의 기준 번호. 발행이 없던 때 세운 계획이면 0
 * @param changes      그 이후 발행된 변경과 검증 유효성
 */
public record PlanChangesResponse(int sinceVersion, DesignChangesResponse changes) {
}
