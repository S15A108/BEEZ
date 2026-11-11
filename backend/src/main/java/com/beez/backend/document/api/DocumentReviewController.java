package com.beez.backend.document.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.dto.*;
import com.beez.backend.document.dto.ReviewChatDtos.*;
import com.beez.backend.document.service.DocumentReviewChatService;
import com.beez.backend.document.service.DocumentReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Document Review", description = "등록자 보완·비비 결과 연동·문서별 팀장 검토")
@RequestMapping("/api/projects/{projectId}")
public class DocumentReviewController {
    private final DocumentReviewService reviews;
    private final DocumentReviewChatService chat;
    private final DocumentRequestOriginVerifier origins;
    public DocumentReviewController(DocumentReviewService reviews, DocumentReviewChatService chat, DocumentRequestOriginVerifier origins) {
        this.reviews = reviews; this.chat = chat; this.origins = origins;
    }

    @GetMapping("/documents/{documentId}/working-copy")
    @Operation(summary = "문서 작성 내용·검토·제출 이력 조회")
    public DocumentWorkingCopyResponse get(CallerContext caller, @PathVariable long projectId, @PathVariable long documentId) {
        return reviews.get(caller, projectId, documentId);
    }

    @PatchMapping("/documents/{documentId}/working-copy")
    @Operation(summary = "등록자 문서 직접 편집")
    public DocumentWorkingCopyResponse edit(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @Valid @RequestBody WorkingCopyUpdateRequest input) {
        origins.requireTrustedOrigin(request);
        return reviews.edit(caller, projectId, documentId, input);
    }

    @PostMapping(value = "/documents/{documentId}/working-copy/file", consumes = "multipart/form-data")
    @Operation(summary = "등록자 파일 재업로드로 보완")
    public DocumentWorkingCopyResponse reupload(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @RequestParam("expected_revision") long expectedRevision, @RequestParam MultipartFile file) throws IOException {
        origins.requireTrustedOrigin(request);
        if (((org.springframework.web.multipart.MultipartRequest) request).getMultiFileMap().values().stream().mapToInt(List::size).sum() != 1) {
            throw com.beez.backend.designitem.support.DesignJson.invalid("요청당 파일 1개만 업로드할 수 있습니다.");
        }
        return reviews.reupload(caller, projectId, documentId, expectedRevision, file.getOriginalFilename(), file.getBytes());
    }

    @PostMapping("/documents/{documentId}/bee-reviews")
    @Operation(summary = "비비 문서 검토 접수",
            description = "검토는 서버 워커가 한다. 접수했거나 아직 도는 중이면 202 와 요청 상태(작업본의 bee_review_request 와 같은 모양), "
                    + "같은 키로 이미 끝난 검토면 201 과 검토 결과를 돌려준다(S15P31A108-391).")
    public org.springframework.http.ResponseEntity<Object> review(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @Valid @RequestBody DocumentReviewRequest input,
            @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        var accepted = reviews.review(caller, projectId, documentId, input.expectedRevision(), key);
        return accepted.review() != null
                ? org.springframework.http.ResponseEntity.status(HttpStatus.CREATED).body(accepted.review())
                : org.springframework.http.ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted.request());
    }

    @PatchMapping("/documents/{documentId}/bee-reviews/{reviewId}/suggestions/{suggestionId}")
    @Operation(summary = "비비 보완 제안 반영 또는 넘기기")
    public DocumentWorkingCopyResponse suggestion(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long reviewId, @PathVariable long suggestionId,
            @Valid @RequestBody SuggestionDecisionRequest input) {
        origins.requireTrustedOrigin(request);
        return reviews.decideSuggestion(caller, projectId, documentId, reviewId, suggestionId, input);
    }


    @PostMapping("/documents/{documentId}/bee-reviews/{reviewId}/suggestions/{suggestionId}/messages")
    @Operation(summary = "DOC-A22 보완 제안에 대해 비비와 대화 — 본문은 바꾸지 않는다")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse message(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long reviewId, @PathVariable long suggestionId,
            @Valid @RequestBody MessageRequest input, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return chat.discuss(caller, projectId, documentId, reviewId, suggestionId, input, key);
    }

    @PostMapping("/documents/{documentId}/bee-reviews/{reviewId}/requests")
    @Operation(summary = "DOC-A23 비비에게 직접 보완 요청 — 같은 검토에 새 PATCH 제안을 더한다")
    public org.springframework.http.ResponseEntity<DirectResponse> directRequest(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long reviewId, @Valid @RequestBody DirectRequest input,
            @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        var answer = chat.request(caller, projectId, documentId, reviewId, input, key);
        // 새 제안이 붙었으면 201, 비비가 고칠 곳이 없다고 답했으면 아무것도 만들지 않았으므로 200.
        return org.springframework.http.ResponseEntity.status(answer.isNoChange() ? HttpStatus.OK : HttpStatus.CREATED).body(answer);
    }

    @PostMapping("/documents/{documentId}/review-submissions/{submissionId}/withdraw")
    @Operation(summary = "DOC-A24 팀장 검토 요청 취소 — 결정 전에만, 이력은 보존")
    public DocumentWorkingCopyResponse withdraw(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long submissionId, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return reviews.withdraw(caller, projectId, documentId, submissionId, key);
    }

    @PostMapping("/documents/{documentId}/discard")
    @Operation(summary = "DOC-A25 문서 등록 취소 — 등록자·팀장, 승인된 적 없는 문서만, 이력은 보존")
    public DocumentDiscardResponse discard(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return reviews.discard(caller, projectId, documentId, key);
    }

    @PostMapping("/documents/{documentId}/review-submissions")
    @Operation(summary = "팀장 검토 요청·재제출")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentSubmissionResponse submit(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @Valid @RequestBody DocumentSubmissionRequest input,
            @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return reviews.submit(caller, projectId, documentId, input, key);
    }

    @PostMapping("/documents/{documentId}/review-submissions/{submissionId}/decision")
    @Operation(summary = "문서별 팀장 승인 또는 사유를 포함한 반려")
    public DocumentSubmissionResponse decision(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long submissionId, @Valid @RequestBody DocumentDecisionRequest input) {
        origins.requireTrustedOrigin(request);
        return reviews.decide(caller, projectId, documentId, submissionId, input);
    }

    @GetMapping("/document-review-submissions")
    @Operation(summary = "문서 검토 요청 목록")
    public List<DocumentSubmissionResponse> submissions(CallerContext caller, @PathVariable long projectId,
            @RequestParam(required = false) String status) { return reviews.submissions(caller, projectId, status); }

    @GetMapping("/documents/{documentId}/review-submissions/{submissionId}")
    @Operation(summary = "제출 당시 본문·비비 검토 상세")
    public DocumentSubmissionDetailResponse submission(CallerContext caller, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long submissionId) {
        return reviews.submission(caller, projectId, documentId, submissionId);
    }

}
