package com.beez.backend.task.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskBreakdownResponse;
import com.beez.backend.task.service.TaskBreakdownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "task", description = "작업 계획·조회 (TSK)")
public class TaskBreakdownController {
    private final TaskBreakdownService breakdown;

    public TaskBreakdownController(TaskBreakdownService breakdown) {
        this.breakdown = breakdown;
    }

    @Operation(summary = "승인 기준에서 역할별 작업 일괄 생성 (사람 팀장만)")
    @PostMapping("/api/projects/{id}/task-breakdown")
    public TaskBreakdownResponse generate(CallerContext caller, @PathVariable("id") long projectId) {
        return breakdown.generate(caller, projectId);
    }
}
