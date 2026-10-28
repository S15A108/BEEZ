package com.beez.backend.invitation;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.auth.SecurityCallerResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MEM-A01 ~ A05. 컨트롤러는 HTTP ↔ 서비스 변환만 한다. 규칙·권한은 전부 InvitationService 안에 있다.
 * Idempotency-Key 는 아직 처리하지 않는다 (저장 방식 Q12 미결, ProjectController 와 같음).
 */
@Tag(name = "invitation", description = "팀원 초대 (MEM)")
@RestController
public class InvitationController {

    private final InvitationService invitations;

    public InvitationController(InvitationService invitations) {
        this.invitations = invitations;
    }

    @Operation(summary = "MEM-A01 팀원 초대 생성 (팀장, 초대 링크 반환)")
    @PostMapping("/api/projects/{id}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedInvitationResponse create(CallerContext caller, @PathVariable("id") Long projectId,
                                            @Valid @RequestBody(required = false) CreateInvitationRequest body) {
        return invitations.create(caller, projectId, body);
    }

    @Operation(summary = "MEM-A02 초대 목록 (팀장, 커서)")
    @GetMapping("/api/projects/{id}/invitations")
    public PageResponse<InvitationResponse> list(CallerContext caller, @PathVariable("id") Long projectId,
                                                 @RequestParam(name = "cursor", required = false) String cursor,
                                                 @RequestParam(name = "limit", required = false) Integer limit) {
        return invitations.list(caller, projectId, cursor, limit);
    }

    @Operation(summary = "MEM-A03 초대 폐기 (팀장)")
    @DeleteMapping("/api/projects/{id}/invitations/{invitation_id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(CallerContext caller, @PathVariable("id") Long projectId,
                       @PathVariable("invitation_id") Long invitationId) {
        invitations.revoke(caller, projectId, invitationId);
    }

    /** PUBLIC 경로라 CallerContext 인자(없으면 401)를 받지 않고, 세션이 있는지만 본다. */
    @Operation(summary = "MEM-A04 초대 미리보기 (로그인 불필요)")
    @PostMapping("/api/invitations/preview")
    public InvitationPreviewResponse preview(@Valid @RequestBody InvitationTokenRequest body) {
        boolean loggedIn = SecurityCallerResolver.find().filter(CallerContext::isHuman).isPresent();
        return invitations.preview(body.token(), loggedIn);
    }

    @Operation(summary = "MEM-A05 초대 수락 (로그인 계정이 MEMBER 로 참여)")
    @PostMapping("/api/invitations/accept")
    public AcceptedInvitationResponse accept(CallerContext caller, @Valid @RequestBody InvitationTokenRequest body) {
        return invitations.accept(caller, body.token());
    }
}
