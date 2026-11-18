package com.beez.backend.task.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskPlanProposalApplyResponse;
import com.beez.backend.task.dto.TaskPlanProposalResponse;
import com.beez.backend.task.service.TaskPlanProposalService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/tasks/{task_id}/plan-revision-proposals")
public class TaskPlanProposalController {
    private final TaskPlanProposalService service;
    public TaskPlanProposalController(TaskPlanProposalService service) { this.service = service; }

    @PostMapping
    public TaskPlanProposalResponse propose(CallerContext caller, @PathVariable("id") Long projectId,
                                             @PathVariable("task_id") Long taskId) {
        return service.propose(caller, projectId, taskId);
    }

    @PostMapping("/{proposal_id}/apply")
    public TaskPlanProposalApplyResponse apply(CallerContext caller, @PathVariable("id") Long projectId,
            @PathVariable("task_id") Long taskId, @PathVariable("proposal_id") Long proposalId) {
        return service.apply(caller, projectId, taskId, proposalId);
    }
}
