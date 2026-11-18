package com.beez.backend.document.api;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.DocumentKind;
import com.beez.backend.document.DocumentStage;
import com.beez.backend.document.service.DocumentApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Tag(name = "Documents", description = "문서 업로드·버전·조회·분석")
public class DocumentController {
    private final DocumentApplicationService documents;
    private final DocumentRequestOriginVerifier origins;
    public DocumentController(DocumentApplicationService documents, DocumentRequestOriginVerifier origins) {
        this.documents = documents;
        this.origins = origins;
    }

    @PostMapping(value = "/api/projects/{projectId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "DOC-A01 필수 문서 업로드")
    public ResponseEntity<Map<String, Object>> upload(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @RequestParam MultipartFile file, @RequestParam DocumentKind kind, @RequestParam String title,
            @RequestParam(required = false) String domain,
            @RequestHeader("Idempotency-Key") String key) throws IOException {
        origins.requireTrustedOrigin(request);
        requireSingleFile(request);
        return ResponseEntity.status(201).body(documents.upload(caller, projectId, kind, title, domain,
                file.getOriginalFilename(), file.getBytes(), key));
    }

    @GetMapping("/api/projects/{projectId}/documents")
    @Operation(summary = "DOC-A02 문서 목록")
    public Map<String, Object> list(CallerContext caller, @PathVariable long projectId,
            @RequestParam(required = false) DocumentKind kind, @RequestParam(required = false) String domain,
            @RequestParam(required = false) DocumentStage stage,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return documents.list(caller, projectId, kind, domain, stage, cursor, limit);
    }

    @GetMapping("/api/projects/{projectId}/documents/{documentId}")
    @Operation(summary = "DOC-A03 원문 조회 — 기본은 현재 승인본")
    public Map<String, Object> get(CallerContext caller, @PathVariable long projectId, @PathVariable long documentId,
            @RequestParam(name = "version_no", required = false) Integer versionNo) {
        return documents.get(caller, projectId, documentId, versionNo);
    }

    @GetMapping("/api/projects/{projectId}/documents/{documentId}/versions")
    @Operation(summary = "DOC-A05 문서 버전 목록")
    public Map<String, Object> versions(CallerContext caller, @PathVariable long projectId, @PathVariable long documentId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return documents.versions(caller, projectId, documentId, cursor, limit);
    }

    @GetMapping("/api/projects/{projectId}/documents/{documentId}/versions/{versionNo}/file")
    @Operation(summary = "DOC-A04 원본 다운로드")
    public ResponseEntity<byte[]> file(CallerContext caller, @PathVariable long projectId,
            @PathVariable long documentId, @PathVariable int versionNo) throws IOException {
        var download = documents.download(caller, projectId, documentId, versionNo);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(download.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(download.filename()).build().toString())
                .header("X-Content-Type-Options", "nosniff").cacheControl(CacheControl.noStore()).body(download.bytes());
    }

    @GetMapping("/api/projects/{projectId}/documents/{documentId}/diff")
    @Operation(summary = "DOC-A06 문서 버전 비교")
    public Map<String, Object> diff(CallerContext caller, @PathVariable long projectId, @PathVariable long documentId,
            @RequestParam("from_version") int from, @RequestParam("to_version") int to) {
        return documents.diff(caller, projectId, documentId, from, to);
    }

    @PatchMapping("/api/projects/{projectId}/documents/{documentId}")
    @Operation(summary = "DOC-A07 문서 분류 수정")
    public Map<String, Object> classify(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @RequestBody Map<String, String> body,
            @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return documents.classify(caller, projectId, documentId, body, key);
    }

    @PostMapping(value = "/api/projects/{projectId}/documents/{documentId}/draft-versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "DOC-A12 변경용 원문 업로드")
    public ResponseEntity<Map<String, Object>> draft(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @RequestParam MultipartFile file,
            @RequestParam("base_document_version_id") long baseId,
            @RequestHeader("Idempotency-Key") String key) throws IOException {
        origins.requireTrustedOrigin(request);
        requireSingleFile(request);
        return ResponseEntity.status(201).body(documents.draft(caller, projectId, documentId, baseId,
                file.getOriginalFilename(), file.getBytes(), key));
    }

    @GetMapping("/api/projects/{projectId}/documents/{documentId}/analysis")
    @Operation(summary = "PLN-A01 원문·색인 실행 상태")
    public Map<String, Object> analysis(CallerContext caller, @PathVariable long projectId,
            @PathVariable long documentId, @RequestParam("document_version_id") long versionId) {
        return documents.analysis(caller, projectId, documentId, versionId);
    }

    public record Retry(long document_version_id) { }
    @PostMapping("/api/projects/{projectId}/documents/{documentId}/analysis/retry")
    @Operation(summary = "PLN-A02 실패 분석 재시도")
    public ResponseEntity<Map<String, Object>> retry(CallerContext caller, HttpServletRequest request, @PathVariable long projectId,
            @PathVariable long documentId, @RequestBody Retry body, @RequestHeader("Idempotency-Key") String key) {
        origins.requireTrustedOrigin(request);
        return ResponseEntity.accepted().body(documents.retry(caller, projectId,
                documentId, body.document_version_id(), key));
    }

    @GetMapping("/api/projects/{projectId}/search")
    @Operation(summary = "DOC-A11 현재 승인 기준 문서·항목 검색")
    public Map<String, Object> search(CallerContext caller, @PathVariable long projectId,
            @RequestParam String q, @RequestParam String mode, @RequestParam(required = false) String domain,
            @RequestParam(required = false) String kind, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return documents.search(caller, projectId, q, mode, domain, kind, cursor, limit);
    }

    @GetMapping("/api/projects/{projectId}/baselines/current")
    @Operation(summary = "DOC-A08 현재 기준 설계 조회")
    public Map<String, Object> currentBaseline(CallerContext caller, @PathVariable long projectId) {
        return documents.baseline(caller, projectId, null);
    }

    @GetMapping("/api/projects/{projectId}/baselines/{versionNo}")
    @Operation(summary = "DOC-A10 특정 기준 설계 조회")
    public Map<String, Object> baseline(CallerContext caller, @PathVariable long projectId, @PathVariable int versionNo) {
        return documents.baseline(caller, projectId, versionNo);
    }

    @GetMapping("/api/projects/{projectId}/baselines")
    @Operation(summary = "DOC-A09 기준 설계 이력")
    public Map<String, Object> baselines(CallerContext caller, @PathVariable long projectId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int limit) {
        return documents.baselines(caller, projectId, cursor, limit);
    }

    private void requireSingleFile(HttpServletRequest request) {
        if (!(request instanceof org.springframework.web.multipart.MultipartRequest multipart)
                || multipart.getMultiFileMap().values().stream().mapToInt(java.util.List::size).sum() != 1) {
            throw new com.beez.backend.common.api.ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "요청당 파일 1개만 업로드할 수 있습니다.");
        }
    }
}
