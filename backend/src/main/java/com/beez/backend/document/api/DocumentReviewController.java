package com.beez.backend.document.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.dto.*;
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
    private final DocumentRequestOriginVerifier origins;
    public DocumentReviewController(DocumentReviewService reviews, DocumentRequestOriginVerifier origins) {
        this.reviews = reviews; this.origins = origins;
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
    @Operation(summary = "비비 문서 검토 요청·결과 수신")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentBeeReviewResponse review(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @Valid @RequestBody DocumentReviewRequest input,
            @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return reviews.review(caller, projectId, documentId, input.expectedRevision(), key);
    }

    @PatchMapping("/documents/{documentId}/bee-reviews/{reviewId}/suggestions/{suggestionId}")
    @Operation(summary = "비비 보완 제안 반영 또는 넘기기")
    public DocumentWorkingCopyResponse suggestion(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable long reviewId, @PathVariable long suggestionId,
            @Valid @RequestBody SuggestionDecisionRequest input) {
        origins.requireTrustedOrigin(request);
        return reviews.decideSuggestion(caller, projectId, documentId, reviewId, suggestionId, input);
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
