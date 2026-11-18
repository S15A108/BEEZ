package com.beez.backend.mcp.dto;

import com.beez.backend.task.dto.TaskPlanInput;

/**
 * MCP-10 upsert_task_plan 도구 인수 (02-mcp-tools). 어댑터(-179)가 그대로 넘긴다.
 *
 * @param plan                작업 계획 (필수)
 * @param taskId              수정할 작업. 최초 생성이면 null
 * @param expectedRevisionNo  수정 시 필수 — 현재 계획 번호
 * @param expectedLockVersion 수정 시 필수 — 작업 잠금 번호
 * @param idempotencyKey      필수. 처리 방식은 Q12 확정 전이라 받기만 한다
 */
public record UpsertTaskPlanArgs(
        TaskPlanInput plan,
        String taskId,
        Integer expectedRevisionNo,
        Integer expectedLockVersion,
        String idempotencyKey
) {
}
