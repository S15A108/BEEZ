package com.beez.backend.meeting.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import com.beez.backend.meeting.service.MeetingRevisionDraftService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects/{projectId}/meetings/{meetingId}/revision-drafts")
public class MeetingRevisionDraftController {
    private final MeetingRevisionDraftService service;
    private final DocumentRequestOriginVerifier origins;
    public MeetingRevisionDraftController(MeetingRevisionDraftService service, DocumentRequestOriginVerifier origins) {
        this.service=service; this.origins=origins;
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(CallerContext caller, HttpServletRequest request, @PathVariable("projectId") long project,
            @PathVariable("meetingId") String meeting, @RequestHeader("Idempotency-Key") String key,
            @RequestBody MeetingRevisionDraftService.Request body) {
        origins.requireTrustedOrigin(request);
        return service.create(caller,project,meeting,body,key);
    }
    @GetMapping("/latest")
    public Map<String,Object> latest(CallerContext caller, @PathVariable("projectId") long project,
            @PathVariable("meetingId") String meeting) { return service.get(caller,project,meeting,null); }
    @GetMapping("/{draftId}")
    public Map<String,Object> get(CallerContext caller, @PathVariable("projectId") long project,
            @PathVariable("meetingId") String meeting, @PathVariable("draftId") String draft) {
        return service.get(caller,project,meeting,draft);
    }
}
