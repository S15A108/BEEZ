package com.beez.backend.task.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskBreakdownJobResponse;
import com.beez.backend.task.dto.TaskBreakdownJobResponse.Status;
import com.beez.backend.task.service.TaskBreakdownService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.NullNode;

@RestController
@Tag(name = "task", description = "작업 계획·조회 (TSK)")
public class TaskBreakdownController {
    private final TaskBreakdownService breakdown;

    public TaskBreakdownController(TaskBreakdownService breakdown) {
        this.breakdown = breakdown;
    }

    @Operation(summary = "승인 기준에서 역할별 작업 일괄 생성 접수 (사람 팀장만)")
    @PostMapping("/api/projects/{id}/task-breakdown")
    public ResponseEntity<TaskBreakdownJobResponse> generate(CallerContext caller, @PathVariable("id") long projectId) {
        TaskBreakdownJobResponse job = breakdown.accept(caller, projectId);
        return job.status() == Status.COMPLETED ? ResponseEntity.ok(job) : ResponseEntity.accepted().body(job);
    }

    @Operation(summary = "역할별 작업 일괄 생성 상태 조회 (사람 팀장만)")
    @GetMapping(value = "/api/projects/{id}/task-breakdown", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object latest(CallerContext caller, @PathVariable("id") long projectId) {
        TaskBreakdownJobResponse job = breakdown.latest(caller, projectId);
        return job == null ? NullNode.getInstance() : job;
    }
}
