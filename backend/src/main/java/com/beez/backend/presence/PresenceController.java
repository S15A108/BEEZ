package com.beez.backend.presence;

import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Presence", description = "팀원 접속 상태·위치")
public class PresenceController {

    private final PresenceService presence;

    public PresenceController(PresenceService presence) {
        this.presence = presence;
    }

    @Operation(summary = "MON-A06 내 접속 상태 보고")
    @PutMapping("/api/projects/{id}/presence")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(CallerContext caller, @PathVariable("id") long projectId,
                       @RequestBody(required = false) PresenceReportRequest body) {
        presence.report(caller, projectId, body);
    }

    @Operation(summary = "MON-A07 팀원 접속 현황")
    @GetMapping("/api/projects/{id}/presence")
    public PresenceResponse list(CallerContext caller, @PathVariable("id") long projectId) {
        return presence.list(caller, projectId);
    }
}
