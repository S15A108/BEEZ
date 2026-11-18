package com.beez.backend.activity;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 활동 타임라인 (S15P31A108-183, MON-A04) */
@Tag(name = "monitoring", description = "대시보드 조회 (MON)")
@RestController
@RequestMapping("/api/projects/{id}")
public class ActivityTimelineController {

    private final ActivityTimelineService timeline;

    public ActivityTimelineController(ActivityTimelineService timeline) {
        this.timeline = timeline;
    }

    @Operation(summary = "MON-A04 프로젝트 활동 타임라인")
    @GetMapping("/activities")
    public PageResponse<ActivityResponse> activities(CallerContext caller, @PathVariable("id") Long projectId,
                                                     @RequestParam(name = "type", required = false) String type,
                                                     @RequestParam(name = "actor_type", required = false) String actorType,
                                                     @RequestParam(name = "member_id", required = false) String memberId,
                                                     @RequestParam(name = "agent_session_id", required = false)
                                                     String agentSessionId,
                                                     @RequestParam(name = "change_proposal_id", required = false)
                                                     String changeProposalId,
                                                     @RequestParam(name = "cursor", required = false) String cursor,
                                                     @RequestParam(name = "limit", required = false) Integer limit) {
        // 필터 해석(400)은 서비스가 권한 확인(401·404) 뒤에 한다. 경로 id·limit 형식 오류는 다른 목록 API 처럼
        // 스프링 바인딩 단계의 400 이라 권한 확인보다 먼저다(프로젝트 존재 여부는 드러나지 않는다)
        return timeline.timeline(caller, projectId,
                () -> TimelineFilter.of(type, actorType, memberId, agentSessionId, changeProposalId), cursor, limit);
    }
}
