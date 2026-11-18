package com.beez.backend.task.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskPlanDraftRequest;
import com.beez.backend.task.service.TaskPlanDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 작업 계획 문서 초안 (S15P31A108-385). 규칙·권한은 서비스 안에 있다. 응답은 저장하지 않는다. */
@Tag(name = "task", description = "작업 계획·조회 (TSK)")
@RestController
@RequestMapping("/api/projects/{id}/task-plan-drafts")
public class TaskPlanDraftController {

    private final TaskPlanDraftService service;

    public TaskPlanDraftController(TaskPlanDraftService service) {
        this.service = service;
    }

    @Operation(summary = "작업 계획 문서 초안 (승인 문서 발췌로 계획 칸 초안과 설계 항목 연결 후보를 받는다. 저장하지 않음)")
    @PostMapping
    public Map<String, Object> draft(CallerContext caller, @PathVariable("id") Long projectId,
                                     @Valid @RequestBody TaskPlanDraftRequest body) {
        return service.draft(caller, projectId, body);
    }
}
