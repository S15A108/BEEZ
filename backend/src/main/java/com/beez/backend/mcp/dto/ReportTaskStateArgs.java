package com.beez.backend.mcp.dto;

import com.beez.backend.task.dto.CompletionCheck;
import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.WaitingReason;
import java.util.List;
import java.util.Map;

/** MCP-12 report_task_state 도구 인수 (02-mcp-tools). REST TSK-A07 요청에 task_id·idempotency_key 가 더해진 모양 */
public record ReportTaskStateArgs(
        String taskId,
        TaskEvent event,
        String planRevisionId,
        Integer expectedLockVersion,
        String validationId,
        WaitingReason waitingReason,
        String summary,
        List<Map<String, Object>> delta,
        List<CompletionCheck> completionChecks,
        String idempotencyKey
) {
}
