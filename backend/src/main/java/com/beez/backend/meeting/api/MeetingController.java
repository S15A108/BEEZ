package com.beez.backend.meeting.api;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.meeting.dto.AddParticipantsRequest;
import com.beez.backend.meeting.dto.CreateMeetingEntryRequest;
import com.beez.backend.meeting.dto.MeetingEntryResponse;
import com.beez.backend.meeting.dto.MeetingResponse;
import com.beez.backend.meeting.service.MeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MTG-A02·A03·A04·A06·A10·A14 회의 (S15P31A108-205). 회의를 만드는 API 는 없다 — 비비가 충돌 시 내부 서비스로 연다.
 * 쓰기 요청은 Origin 을 먼저 보고, 본문 검증은 서비스에서 한다(@Valid 를 쓰면 본문 오류가 Origin 검사보다 먼저 400 이 된다).
 */
@Tag(name = "meeting", description = "회의 조회·참여자·기록 (MTG-A02·A03·A04·A06·A10·A14)")
@RestController
@RequestMapping("/api/projects/{projectId}/meetings")
public class MeetingController {

    private final MeetingService meetings;
    private final DocumentRequestOriginVerifier origins;

    public MeetingController(MeetingService meetings, DocumentRequestOriginVerifier origins) {
        this.meetings = meetings;
        this.origins = origins;
    }

    @Operation(summary = "회의 목록 (MTG-A02). status 필터, 최근 회의 먼저, 커서")
    @GetMapping
    public PageResponse<MeetingResponse> list(CallerContext caller, @PathVariable("projectId") Long projectId,
                                              @RequestParam(value = "status", required = false) String status,
                                              @RequestParam(value = "cursor", required = false) String cursor,
                                              @RequestParam(value = "limit", required = false) Integer limit) {
        return meetings.list(caller, projectId, status, cursor, limit);
    }

    @Operation(summary = "회의 상세 (MTG-A03)")
    @GetMapping("/{meetingId}")
    public MeetingResponse get(CallerContext caller, @PathVariable("projectId") Long projectId,
                               @PathVariable("meetingId") String meetingId) {
        return meetings.get(caller, projectId, meetingId);
    }

    @Operation(summary = "회의 참여자 추가 (MTG-A04). 팀장 또는 회의 참여자")
    @PostMapping("/{meetingId}/participants")
    public MeetingResponse addParticipants(CallerContext caller, HttpServletRequest request,
                                           @PathVariable("projectId") Long projectId,
                                           @PathVariable("meetingId") String meetingId,
                                           @RequestBody AddParticipantsRequest body) {
        origins.requireTrustedOrigin(request);
        return meetings.addParticipants(caller, projectId, meetingId, body);
    }

    @Operation(summary = "회의 참여자 제외 (MTG-A14). 팀장만, 멱등")
    @DeleteMapping("/{meetingId}/participants/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeParticipant(CallerContext caller, HttpServletRequest request,
                                  @PathVariable("projectId") Long projectId,
                                  @PathVariable("meetingId") String meetingId,
                                  @PathVariable("memberId") String memberId) {
        origins.requireTrustedOrigin(request);
        meetings.removeParticipant(caller, projectId, meetingId, memberId);
    }

    @Operation(summary = "회의 텍스트 기록 (MTG-A06). 회의 참여자, Idempotency-Key 선택")
    @PostMapping("/{meetingId}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public MeetingEntryResponse createEntry(CallerContext caller, HttpServletRequest request,
                                            @PathVariable("projectId") Long projectId,
                                            @PathVariable("meetingId") String meetingId,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                            @RequestBody CreateMeetingEntryRequest body) {
        origins.requireTrustedOrigin(request);
        return meetings.createEntry(caller, projectId, meetingId, body, idempotencyKey);
    }

    @Operation(summary = "회의 기록 목록 (MTG-A10). 작성 순서, after_entry_id 로 증분")
    @GetMapping("/{meetingId}/entries")
    public PageResponse<MeetingEntryResponse> listEntries(CallerContext caller,
                                                         @PathVariable("projectId") Long projectId,
                                                         @PathVariable("meetingId") String meetingId,
                                                         @RequestParam(value = "cursor", required = false) String cursor,
                                                         @RequestParam(value = "limit", required = false) Integer limit,
                                                         @RequestParam(value = "after_entry_id", required = false) String afterEntryId) {
        return meetings.listEntries(caller, projectId, meetingId, cursor, limit, afterEntryId);
    }
}
