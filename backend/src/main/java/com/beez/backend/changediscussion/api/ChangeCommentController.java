package com.beez.backend.changediscussion.api;

import com.beez.backend.changediscussion.dto.ChangeCommentResponse;
import com.beez.backend.changediscussion.dto.CreateChangeCommentRequest;
import com.beez.backend.changediscussion.service.ChangeDiscussionService;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.api.DocumentRequestOriginVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** CHG-A12·A13 변경 제안 의견·검토 요청 (S15P31A108-196). 프로젝트 멤버 */
@Tag(name = "change-comment", description = "변경 제안 의견·검토 요청 (CHG-A12·A13)")
@RestController
@RequestMapping("/api/projects/{projectId}/change-proposals/{proposalId}/comments")
public class ChangeCommentController {

    private final ChangeDiscussionService discussion;
    private final DocumentRequestOriginVerifier origins;

    public ChangeCommentController(ChangeDiscussionService discussion, DocumentRequestOriginVerifier origins) {
        this.discussion = discussion;
        this.origins = origins;
    }

    @Operation(summary = "변경 제안 의견·검토 요청 등록 (CHG-A12). 의견은 승인이 아니다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChangeCommentResponse create(CallerContext caller, HttpServletRequest request,
                                        @PathVariable("projectId") Long projectId,
                                        @PathVariable("proposalId") String proposalId,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                        @RequestBody CreateChangeCommentRequest body) {
        // @Valid 를 쓰면 본문 오류가 Origin 검사보다 먼저 400 이 되므로, Origin 을 먼저 보고 본문은 서비스에서 검증한다
        origins.requireTrustedOrigin(request);
        return discussion.create(caller, projectId, proposalId, body, idempotencyKey);
    }

    @Operation(summary = "변경 제안 의견 목록 (CHG-A13). 작성 순서, 커서")
    @GetMapping
    public PageResponse<ChangeCommentResponse> list(CallerContext caller,
                                                    @PathVariable("projectId") Long projectId,
                                                    @PathVariable("proposalId") String proposalId,
                                                    @RequestParam(value = "revision", required = false) Integer revision,
                                                    @RequestParam(value = "cursor", required = false) String cursor,
                                                    @RequestParam(value = "limit", required = false) Integer limit) {
        return discussion.list(caller, projectId, proposalId, revision, cursor, limit);
    }
}
