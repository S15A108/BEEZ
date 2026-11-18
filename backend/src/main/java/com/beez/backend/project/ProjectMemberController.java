package com.beez.backend.project;

import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * MEM-A06 ~ A10. 컨트롤러는 HTTP ↔ 서비스 변환만 한다. 규칙·권한은 전부 ProjectMemberService 안에 있다.
 * Idempotency-Key 는 아직 처리하지 않는다 (저장 방식 Q12 미결, ProjectController 와 같음).
 */
@Tag(name = "member", description = "팀원 관리 (MEM)")
@RestController
public class ProjectMemberController {

    private final ProjectMemberService members;

    public ProjectMemberController(ProjectMemberService members) {
        this.members = members;
    }

    @Operation(summary = "MEM-A06 팀원 목록 (커서)")
    @GetMapping("/api/projects/{id}/members")
    public PageResponse<MemberResponse> list(CallerContext caller, @PathVariable("id") Long projectId,
                                             @RequestParam(name = "cursor", required = false) String cursor,
                                             @RequestParam(name = "limit", required = false) Integer limit) {
        return members.list(caller, projectId, cursor, limit);
    }

    @Operation(summary = "MEM-A07 팀원 담당 영역·표시 수정 (팀장)")
    @PatchMapping("/api/projects/{id}/members/{member_id}")
    public MemberResponse update(CallerContext caller, @PathVariable("id") Long projectId,
                                 @PathVariable("member_id") Long memberId, @RequestBody UpdateMemberRequest body) {
        return members.update(caller, projectId, memberId, body);
    }

    @Operation(summary = "MEM-A08 팀장 위임 (팀장)")
    @PostMapping("/api/projects/{id}/leadership-transfer")
    public LeadershipTransferResponse transferLeadership(CallerContext caller, @PathVariable("id") Long projectId,
                                                         @Valid @RequestBody TransferLeadershipRequest body) {
        return members.transferLeadership(caller, projectId, body);
    }

    @Operation(summary = "MEM-A09 팀원 제외 (팀장, 열린 작업 인계)")
    @PostMapping("/api/projects/{id}/members/{member_id}/remove")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(CallerContext caller, @PathVariable("id") Long projectId,
                       @PathVariable("member_id") Long memberId,
                       @RequestBody(required = false) EndMembershipRequest body) {
        members.remove(caller, projectId, memberId, body);
    }

    @Operation(summary = "MEM-A10 프로젝트 나가기 (열린 작업 인계)")
    @PostMapping("/api/projects/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(CallerContext caller, @PathVariable("id") Long projectId,
                      @RequestBody(required = false) EndMembershipRequest body) {
        members.leave(caller, projectId, body);
    }
}
