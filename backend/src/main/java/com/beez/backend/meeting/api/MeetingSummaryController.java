package com.beez.backend.meeting.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.meeting.dto.MeetingSummaryResponse;
import com.beez.backend.meeting.dto.ReviseSummaryRequest;
import com.beez.backend.meeting.dto.SummarizeRequest;
import com.beez.backend.meeting.service.MeetingSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTG-A11 비비 회의 정리, MTG-A12 회의 정리 수정 요청 (S15P31A108-235). 팀장이면서 회의 참여자만, Idempotency-Key 필수.
 * 쓰기 요청은 Origin 을 먼저 보고, 본문 검증은 서비스에서 한다(@Valid 를 쓰면 본문 오류가 Origin 검사보다 먼저 400 이 된다).
 */
@Tag(name = "meeting-summary", description = "비비 회의 정리·수정 요청 (MTG-A11·A12)")
@RestController
@RequestMapping("/api/projects/{projectId}/meetings/{meetingId}/bee-summary")
public class MeetingSummaryController {

    private final MeetingSummaryService summaries;
    private final DocumentRequestOriginVerifier origins;

    public MeetingSummaryController(MeetingSummaryService summaries, DocumentRequestOriginVerifier origins) {
        this.summaries = summaries;
        this.origins = origins;
    }

    @GetMapping("/latest")
    public MeetingSummaryResponse latest(CallerContext caller, @PathVariable("projectId") Long projectId,
                                         @PathVariable("meetingId") String meetingId) {
        return summaries.latest(caller, projectId, meetingId);
    }

    @Operation(summary = "비비 회의 정리 (MTG-A11). 팀장·회의 참여자, Idempotency-Key 필수")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingSummaryResponse summarize(CallerContext caller, HttpServletRequest request,
                                            @PathVariable("projectId") Long projectId,
                                            @PathVariable("meetingId") String meetingId,
                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @RequestBody SummarizeRequest body) {
        origins.requireTrustedOrigin(request);
        return summaries.summarize(caller, projectId, meetingId, body, idempotencyKey);
    }

    @Operation(summary = "회의 정리 수정 요청 (MTG-A12). 최신 정리만, Idempotency-Key 필수")
    @PostMapping("/{summaryId}/revise")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingSummaryResponse revise(CallerContext caller, HttpServletRequest request,
                                         @PathVariable("projectId") Long projectId,
                                         @PathVariable("meetingId") String meetingId,
                                         @PathVariable("summaryId") String summaryId,
                                         @RequestHeader("Idempotency-Key") String idempotencyKey,
                                         @RequestBody ReviseSummaryRequest body) {
        origins.requireTrustedOrigin(request);
        return summaries.revise(caller, projectId, meetingId, summaryId, body, idempotencyKey);
    }
}
