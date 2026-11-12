package com.beez.backend.context.api;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.ContextRecordResponse;
import com.beez.backend.context.dto.ContextRecordSummaryResponse;
import com.beez.backend.context.service.ContextRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CTX-A02 공유 작업 기록 목록·검색, CTX-A03 작업 기록 상세 (S15P31A108-404).
 * 에이전트가 MCP write_context 로 남긴 기록을 사람이 웹에서 본다. 규칙은 MCP find_context·read_context 와 같은
 * {@link ContextRecordService} 에 있다: 공유(SHARED)는 프로젝트 멤버 모두, 나만 보기(PRIVATE)는 작성자만.
 * 기록 쓰기(CTX-A04)와 설계 근거 추천(CTX-A05)은 여기 없다.
 */
@Tag(name = "context-record", description = "공유 작업 기록 조회")
@RestController
@RequestMapping("/api/projects/{projectId}/context-records")
public class ContextRecordController {

    private final ContextRecordService records;

    public ContextRecordController(ContextRecordService records) {
        this.records = records;
    }

    @Operation(summary = "공유 작업 기록 목록·검색 (CTX-A02). 검색어 없이 부르면 보이는 기록 전부를 최신순으로")
    @GetMapping
    public PageResponse<ContextRecordSummaryResponse> list(CallerContext caller,
                                                           @PathVariable("projectId") Long projectId,
                                                           @RequestParam(value = "q", required = false) String q,
                                                           @RequestParam(value = "task_id", required = false) String taskId,
                                                           @RequestParam(value = "kind", required = false) String kind,
                                                           @RequestParam(value = "cursor", required = false) String cursor,
                                                           @RequestParam(value = "limit", required = false) Integer limit) {
        return records.list(caller, projectId, q, taskId, kind, cursor, limit);
    }

    @Operation(summary = "작업 기록 상세 (CTX-A03). 남의 나만 보기 기록은 404")
    @GetMapping("/{recordId}")
    public ContextRecordResponse get(CallerContext caller,
                                     @PathVariable("projectId") Long projectId,
                                     @PathVariable("recordId") Long recordId) {
        return records.readForRest(caller, projectId, recordId);
    }
}
