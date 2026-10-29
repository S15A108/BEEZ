package com.beez.backend.mcp.dto;

import com.beez.backend.designchange.dto.DesignChangesResponse;
import com.beez.backend.task.dto.TaskDetailResponse;

/**
 * MCP-12 응답: 처리된 작업과 관련 설계 변경 요약.
 * changes 는 그 작업 현재 계획을 세운 시점의 기준 이후 변경이다(-166 결정). 기준 설계가 한 번도 없는 프로젝트면 null(생략).
 *
 * @param changesSinceVersion changes 를 계산한 기준 번호. changes 가 없으면 null.
 *                            changes.has_more 이면 get_design_changes(since_version=이 값, task_id=같은 작업, cursor=changes.next_cursor)로 이어 본다
 */
public record ReportTaskStateResult(TaskDetailResponse task, DesignChangesResponse changes, Integer changesSinceVersion) {
}
