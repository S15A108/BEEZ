package com.beez.backend.meeting.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.meeting.dto.CloseMeetingRequest;
import com.beez.backend.meeting.dto.CloseMeetingResponse;
import com.beez.backend.meeting.service.MeetingCloseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTG-A13 회의 끝내기 (S15P31A108-297). 팀장이면서 회의 참여자만, Idempotency-Key 필수. 정리에 바꿀 문서가 있으면 변경 제안
 * 1건을 만들고 회의를 닫는다. 쓰기 요청은 Origin 을 먼저 보고, 본문 검증은 서비스에서 한다.
 */
@Tag(name = "meeting-close", description = "회의 끝내기와 변경 제안 생성 (MTG-A13)")
@RestController
@RequestMapping("/api/projects/{projectId}/meetings/{meetingId}")
public class MeetingCloseController {

    private final MeetingCloseService closing;
    private final DocumentRequestOriginVerifier origins;

    public MeetingCloseController(MeetingCloseService closing, DocumentRequestOriginVerifier origins) {
        this.closing = closing;
        this.origins = origins;
    }

    @Operation(summary = "회의 끝내기 (MTG-A13). 팀장·회의 참여자, Idempotency-Key 필수. 문서 변경이 있으면 변경 제안 1건 생성")
    @PostMapping("/close")
    public CloseMeetingResponse close(CallerContext caller, HttpServletRequest request,
                                      @PathVariable("projectId") Long projectId,
                                      @PathVariable("meetingId") String meetingId,
                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                      @RequestBody CloseMeetingRequest body) {
        origins.requireTrustedOrigin(request);
        return closing.close(caller, projectId, meetingId, body, idempotencyKey);
    }
}
