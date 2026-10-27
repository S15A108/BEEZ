package com.beez.backend.contextaccess.api;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.contextaccess.dto.ContextAccessLogResponse;
import com.beez.backend.contextaccess.service.ContextAccessLogQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CTX-A06 컨텍스트 조회 이력 (S15P31A108-165). 팀장만 */
@Tag(name = "context-access-log", description = "누가 어떤 설계 버전·패킷·기록을 봤는지")
@RestController
@RequestMapping("/api/projects/{projectId}/context-access-logs")
public class ContextAccessLogController {

    private final ContextAccessLogQueryService query;

    public ContextAccessLogController(ContextAccessLogQueryService query) {
        this.query = query;
    }

    @Operation(summary = "컨텍스트 조회 이력 목록 (CTX-A06). 사람 세션의 팀장만")
    @GetMapping
    public PageResponse<ContextAccessLogResponse> list(CallerContext caller,
                                                       @PathVariable("projectId") Long projectId,
                                                       @RequestParam(value = "task_id", required = false) String taskId,
                                                       @RequestParam(value = "tool", required = false) String tool,
                                                       @RequestParam(value = "cursor", required = false) String cursor,
                                                       @RequestParam(value = "limit", required = false) Integer limit) {
        return query.list(caller, projectId, taskId, tool, cursor, limit);
    }
}
