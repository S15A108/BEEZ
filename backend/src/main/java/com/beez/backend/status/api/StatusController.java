package com.beez.backend.status.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.status.dto.DocumentStatusResponse;
import com.beez.backend.status.dto.ProposalStatusResponse;
import com.beez.backend.status.service.StatusQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 문서·변경 현황 조회 API (S15P31A108-104). 알림·타임라인 이벤트 전달은 별도 PR 이다 */
@RestController
@Tag(name = "Status", description = "문서 승인 현황과 변경 제안 현황")
public class StatusController {

    private final StatusQueryService status;

    public StatusController(StatusQueryService status) {
        this.status = status;
    }

    @GetMapping("/api/projects/{projectId}/documents/summary")
    @Operation(summary = "MON-D01 문서 승인 현황")
    public DocumentStatusResponse documents(CallerContext caller, @PathVariable long projectId) {
        return status.documents(caller, projectId);
    }

    @GetMapping("/api/projects/{projectId}/change-proposals/summary")
    @Operation(summary = "MON-C01 변경 제안 현황")
    public ProposalStatusResponse proposals(CallerContext caller, @PathVariable long projectId) {
        return status.proposals(caller, projectId);
    }
}
