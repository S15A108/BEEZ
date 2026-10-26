package com.beez.backend.task.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskStateInput;
import com.beez.backend.task.service.TaskStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** TSK-A07 작업 상태 보고. 규칙은 전부 TaskStateService 에 있다 (MCP report_task_state 와 공유). */
@Tag(name = "task", description = "작업 계획·조회 (TSK)")
@RestController
@RequestMapping("/api/projects/{id}/tasks/{task_id}")
public class TaskStateController {

    private final TaskStateService stateService;

    public TaskStateController(TaskStateService stateService) {
        this.stateService = stateService;
    }

    @Operation(summary = "TSK-A07 작업 상태 보고 (START·RESUME·WAIT·COMPLETED·CANCEL)")
    @PostMapping("/state-events")
    public TaskDetailResponse report(CallerContext caller, @PathVariable("id") Long projectId,
                                     @PathVariable("task_id") Long taskId, @Valid @RequestBody TaskStateInput body) {
        return stateService.report(caller, projectId, taskId, body);
    }
}
