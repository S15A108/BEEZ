package com.beez.backend.document.service;

import static com.beez.backend.designitem.support.DesignJson.*;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.api.Ids;
import com.beez.backend.document.dto.*;
import com.beez.backend.document.port.DocumentReviewGateway;
import com.beez.backend.document.repository.DocumentReviewRepository;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.validation.DocumentInputValidator;
import com.beez.backend.document.validation.DocumentReviewUsageValidator;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentReviewService {
    private final DocumentReviewRepository repository;
    private final ProjectAccessChecker access;
    private final DocumentInputValidator validator;
    private final DocumentReviewGateway ai;
    private final TransactionTemplate tx;
    private final DocumentOriginalWriter originals;
    private final DocumentStorage storage;

    public DocumentReviewService(DocumentReviewRepository repository, ProjectAccessChecker access,
            DocumentInputValidator validator, DocumentReviewGateway ai, DocumentStorage storage,
            PlatformTransactionManager manager) {
        this.repository = repository; this.access = access; this.validator = validator; this.ai = ai;
        tx = new TransactionTemplate(manager); originals = new DocumentOriginalWriter(storage, manager); this.storage = storage;
    }

    private void member(CallerContext caller, long project) { access.requireMember(caller, project).requireHuman(); }

    private void lock(CallerContext caller, long project, boolean leader) {
        if (leader) access.requireHumanLeader(caller, project); else member(caller, project);
        if (!repository.lockMember(project, caller.accountId(), leader)) {
            if (leader && repository.currentRole(project, caller.accountId()).isPresent()) {
                throw AccessErrors.forbidden("document_leader_changed", "현재 사람 팀장만 심사할 수 있습니다.");
            }
            throw AccessErrors.notFound("document_review_membership_changed");
        }
    }

    private void initialWorkflow(long project) {
        if (repository.hasBaseline(project)) throw conflict("change_proposal_required");
    }

    private Map<String, Object> copy(long project, long document) {
        return repository.workingCopy(project, document).orElseThrow(() -> AccessErrors.notFound("document_working_copy_missing"));
    }

    private Map<String, Object> owned(CallerContext caller, long project, long document) {
        var copy = copy(project, document);
        if (id(copy, "owner_account_id") != caller.accountId()) {
            throw AccessErrors.forbidden("document_author_required", "등록한 담당자만 작성 내용을 변경할 수 있습니다.");
        }
        return copy;
    }

    private void editable(Map<String, Object> copy, long expected) {
        if (expected < 1) throw invalid("expected_revision은 양수여야 합니다.");
        if ("SUBMITTED".equals(copy.get("status"))) throw conflict("submission_locked");
        if (expected != id(copy, "revision")) throw conflict("version_conflict");
    }

    @Transactional(readOnly = true)
    public DocumentWorkingCopyResponse get(CallerContext caller, long project, long document) {
        member(caller, project);
        return view(copy(project, document));
    }

    @Transactional
    public DocumentWorkingCopyResponse edit(CallerContext caller, long project, long document, WorkingCopyUpdateRequest input) {
        lock(caller, project, false);
        initialWorkflow(project);
        var copy = owned(caller, project, document);
        editable(copy, input.expectedRevision());
        var normalized = validator.validate(input.fileName(), input.content().getBytes(StandardCharsets.UTF_8));
        repository.edit(document, normalized.text(), input.fileName());
        return view(copy(project, document));
    }

    @Transactional(propagation = Propagation.NEVER)
    public DocumentWorkingCopyResponse reupload(CallerContext caller, long project, long document, long expected, String filename, byte[] bytes) {
        tx.executeWithoutResult(status -> {
            lock(caller, project, false); initialWorkflow(project);
            editable(owned(caller, project, document), expected);
        });
        var source = validator.validate(filename, bytes);
        return originals.write(bytes, source.extension(), original -> {
            lock(caller, project, false); initialWorkflow(project);
            editable(owned(caller, project, document), expected);
            repository.edit(document, source.text(), filename);
            repository.workingOriginal(document, original.reference());
            return view(copy(project, document));
        });
    }

    private List<Map<String, Object>> references(long project, long document) { return repository.references(project, document); }

    private String referenceHash(List<Map<String, Object>> references) {
        return hash(references.stream().map(r -> List.of(r.get("document_id"), r.get("document_version_id"), r.get("content_hash"))).toList());
    }

    private Map<String, Object> reviewInput(Map<String, Object> copy, List<Map<String, Object>> refs, String key) {
        var available = refs.stream().map(r -> r.get("type").toString()).toList();
        var missing = List.of("API_SPEC", "ERD", "REQ_SPEC").stream()
                .filter(type -> !available.contains(type)).toList();
        return Map.of("project_id", copy.get("project_id").toString(), "document_id", copy.get("document_id").toString(),
                "revision", copy.get("revision"), "content", copy.get("content_text"), "kind", copy.get("type"),
                "request_key", key, "reference_hash", referenceHash(refs), "unavailable_references", missing,
                "references", refs.stream().map(r -> Map.of("document_id", r.get("document_id").toString(),
                        "document_version_id", r.get("document_version_id").toString(), "kind", r.get("type"),
                        "version_no", r.get("version_no"), "content", r.get("content_text"))).toList());
    }

    private void key(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Idempotency-Key는 제어 문자가 없는 1~128자입니다.");
        }
    }

    @Transactional(propagation = Propagation.NEVER)
    public DocumentBeeReviewResponse review(CallerContext caller, long project, long document, long expected, String key) {
        key(key);
        var prepared = tx.execute(status -> {
            lock(caller, project, false);
            var copy = owned(caller, project, document);
            var refs = references(project, document);
            String fingerprint = hash(List.of(expected, referenceHash(refs)));
            var previous = repository.reviewRequest(document, caller.accountId(), key);
            if (previous.isPresent()) {
                var old = previous.get();
                if (!fingerprint.equals(old.get("fingerprint"))) throw conflict("idempotency_conflict");
                if ("COMPLETED".equals(old.get("status"))) return Map.<String, Object>of("replay", old.get("review_id"));
                if ("RUNNING".equals(old.get("status"))) throw conflict("review_in_progress");
                throw unavailable("이 검토 요청은 실패했습니다. 새 요청 키로 다시 검토해 주세요.");
            }
            editable(copy, expected);
            initialWorkflow(project);
            repository.startReview(document, caller.accountId(), key, fingerprint);
            return reviewInput(copy, refs, "document-review-" + document + "-" + UUID.randomUUID());
        });
        if (prepared.containsKey("replay")) return tx.execute(status -> {
            member(caller, project);
            return reviewView(repository.review(document, id(prepared, "replay")).orElseThrow());
        });
        List<Map<String, Object>> usage = List.of();
        try {
            // Network and model work never hold a project lock or DB transaction.
            var envelope = ai.review(prepared);
            usage = DocumentReviewUsageValidator.extract(envelope == null ? null : envelope.get("usage"), prepared.get("request_key").toString());
            if (envelope != null) { envelope = new HashMap<>(envelope); envelope.put("usage", usage); }
            var result = validateResult(envelope, prepared);
            var actualUsage = usage;
            return tx.execute(status -> {
                lock(caller, project, false);
                var copy = owned(caller, project, document);
                editable(copy, expected);
                initialWorkflow(project);
                if (!prepared.get("reference_hash").equals(referenceHash(references(project, document)))) throw conflict("review_stale");
                long review = repository.saveReview(document, expected, prepared.get("reference_hash").toString(), prepared, result, actualUsage);
                for (Object entry : (List<?>) result.get("suggestions")) {
                    var suggestion = (Map<?, ?>) entry;
                    repository.saveSuggestion(review, suggestion.get("explanation").toString(), suggestion.get("original_text").toString(), suggestion.get("replacement_text").toString());
                }
                if (!repository.finishReview(document, caller.accountId(), key, review, null, actualUsage)) throw conflict("review_stale");
                repository.recordUsage(project, actualUsage);
                return reviewView(repository.review(document, review).orElseThrow());
            });
        } catch (RuntimeException failure) {
            if (failure instanceof org.springframework.web.client.RestClientResponseException responseError) {
                try {
                    var errorBody = responseError.getResponseBodyAsString();
                    if (errorBody.length() <= 1048576) usage = DocumentReviewUsageValidator.extract(
                            JSON.readValue(errorBody, Map.class).get("usage"), prepared.get("request_key").toString());
                } catch (RuntimeException ignored) { /* An unstructured transport error has unknown usage. */ }
            }
            if (!usage.isEmpty() && failure instanceof ApiException e && "invalid_ai_output".equals(e.getCode())) {
                usage = new ArrayList<>(usage);
                var last = new HashMap<>(usage.getLast()); last.put("failed", true); usage.set(usage.size() - 1, last);
            }
            var actualUsage = usage;
            tx.executeWithoutResult(status -> {
                repository.finishReview(document, caller.accountId(), key, null,
                        failure instanceof ApiException e ? e.getCode() : "review_unavailable", actualUsage);
                repository.recordUsage(project, actualUsage);
            });
            if (failure instanceof ApiException) throw failure;
            throw unavailable("비비 검토를 완료하지 못했습니다. 잠시 후 다시 요청해 주세요.");
        }
    }

    private Map<String, Object> validateResult(Map<String, Object> envelope, Map<String, Object> input) {
        if (envelope == null || !"MODEL".equals(envelope.get("origin")) || !(envelope.get("result") instanceof Map<?, ?> raw)
                || !(envelope.get("usage") instanceof List<?> usage) || usage.isEmpty()) throw invalidAi();
        if (usage.stream().noneMatch(item -> item instanceof Map<?, ?> u && Boolean.FALSE.equals(u.get("failed"))
                && u.get("model") instanceof String model && !model.isBlank())) throw invalidAi();
        var result = new HashMap<String, Object>();
        for (String field : List.of("document_id", "revision", "reference_hash", "request_key")) {
            if ("revision".equals(field)) {
                if (!(raw.get(field) instanceof Number n) || n.doubleValue() != n.longValue()
                        || n.longValue() != ((Number) input.get(field)).longValue()) throw invalidAi();
            } else if (!Objects.equals(input.get(field), raw.get(field))) throw invalidAi();
            result.put(field, raw.get(field));
        }
        if (!(raw.get("summary") instanceof String summary) || summary.isBlank() || summary.length() > 10000
                || !(raw.get("suggestions") instanceof List<?> suggestions) || suggestions.size() > 30) throw invalidAi();
        String content = input.get("content").toString();
        for (var entry : suggestions) {
            if (!(entry instanceof Map<?, ?> suggestion) || !(suggestion.get("explanation") instanceof String explanation)
                    || explanation.isBlank() || explanation.length() > 10000
                    || !(suggestion.get("original_text") instanceof String original) || original.isEmpty()
                    || !(suggestion.get("replacement_text") instanceof String replacement) || replacement.length() > 100000
                    || content.indexOf(original) < 0 || content.indexOf(original) != content.lastIndexOf(original)) throw invalidAi();
        }
        result.put("summary", summary); result.put("suggestions", suggestions);
        result.put("unavailable_references", input.get("unavailable_references"));
        try { canonical(result); }
        catch (ApiException malformed) { throw invalidAi(); }
        return result;
    }

    private ApiException invalidAi() { return new ApiException(HttpStatus.BAD_GATEWAY, "invalid_ai_output", "비비 검토 결과 형식이 올바르지 않습니다."); }
    private ApiException unavailable(String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "review_unavailable", message); }

    private Map<String, Object> currentReview(long project, long document, long review, long revision) {
        var row = repository.review(document, review).orElseThrow(() -> AccessErrors.notFound("document_review_missing"));
        if (id(row, "resolved_revision") != revision
                || !row.get("reference_hash").equals(referenceHash(references(project, document)))) throw conflict("review_stale");
        return row;
    }

    @Transactional
    public DocumentWorkingCopyResponse decideSuggestion(CallerContext caller, long project, long document,
            long review, long suggestion, SuggestionDecisionRequest input) {
        lock(caller, project, false);
        var copy = owned(caller, project, document);
        editable(copy, input.expectedRevision());
        initialWorkflow(project);
        currentReview(project, document, review, input.expectedRevision());
        var row = repository.suggestions(review).stream().filter(r -> id(r, "id") == suggestion).findFirst()
                .orElseThrow(() -> AccessErrors.notFound("document_suggestion_missing"));
        String decision = "APPLY".equals(input.decision()) ? "APPLIED" : "SKIPPED";
        if (!Set.of("APPLY", "SKIP").contains(input.decision())) throw invalid("제안 처리 방식이 올바르지 않습니다.");
        if (!"PENDING".equals(row.get("decision"))) {
            if (!decision.equals(row.get("decision"))) throw conflict("version_conflict");
            return view(copy);
        }
        long revision = input.expectedRevision();
        if ("APPLIED".equals(decision)) {
            String content = copy.get("content_text").toString(), original = row.get("original_text").toString();
            int start = content.indexOf(original);
            if (start < 0 || start != content.lastIndexOf(original)) throw conflict("review_stale");
            String updated = content.substring(0, start) + row.get("replacement_text") + content.substring(start + original.length());
            var source = validator.validate(copy.get("file_name").toString(), updated.getBytes(StandardCharsets.UTF_8));
            repository.edit(document, source.text(), copy.get("file_name").toString());
            revision++;
        }
        repository.decideSuggestion(suggestion, decision, review, revision);
        return view(copy(project, document));
    }

    @Transactional(propagation = Propagation.NEVER)
    public DocumentSubmissionResponse submit(CallerContext caller, long project, long document, DocumentSubmissionRequest input, String key) {
        key(key);
        long review = Ids.parse(input.reviewId(), "review_id");
        String operation = "submit-document:" + document, fingerprint = hash(input);
        var prepared = tx.execute(status -> {
            lock(caller, project, false);
            var copy = owned(caller, project, document);
            var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
            if (previous.isPresent()) return Map.<String, Object>of("replay", previous.get());
            validateSubmission(project, document, copy, input.expectedRevision(), review);
            return copy;
        });
        if (prepared.containsKey("replay")) return JSON.readValue(prepared.get("replay").toString(), DocumentSubmissionResponse.class);
        byte[] bytes;
        try {
            bytes = prepared.get("storage_key") == null ? prepared.get("content_text").toString().getBytes(StandardCharsets.UTF_8)
                    : storage.read(prepared.get("storage_key").toString());
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException("작성 원본을 읽을 수 없습니다.", e); }
        var source = validator.validate(prepared.get("file_name").toString(), bytes);
        if (!source.text().equals(prepared.get("content_text"))) throw conflict("version_conflict");
        return originals.write(bytes, source.extension(), original -> {
            lock(caller, project, false);
            var copy = owned(caller, project, document);
            var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
            if (previous.isPresent()) return JSON.readValue(previous.get(), DocumentSubmissionResponse.class);
            validateSubmission(project, document, copy, input.expectedRevision(), review);
            long submission = repository.submit(copy, review, original.reference(), source.contentHash(), source.textHash());
            // [TODO] 전진님 공통 알림 서비스 계약 확정 후 팀장 검토 요청 알림 로직 넣기. 수신자·중복 키·전달 보장을 합의한다.
            var response = submissionView(repository.submission(project, document, submission).orElseThrow());
            repository.remember(project, caller.accountId(), operation, key, fingerprint, response);
            return response;
        });
    }

    private void validateSubmission(long project, long document, Map<String, Object> copy, long revision, long review) {
        initialWorkflow(project);
        editable(copy, revision);
        if ("APPROVED".equals(copy.get("status"))) throw conflict("version_conflict");
        currentReview(project, document, review, revision);
        if (repository.suggestions(review).stream().anyMatch(s -> "PENDING".equals(s.get("decision")))) throw conflict("review_decisions_pending");
    }

    @Transactional
    public DocumentSubmissionResponse decide(CallerContext caller, long project, long document, long submission, DocumentDecisionRequest input) {
        lock(caller, project, true);
        var row = repository.submission(project, document, submission).orElseThrow(() -> AccessErrors.notFound("document_submission_missing"));
        String state = switch (input.decision()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT" -> "REJECTED";
            default -> throw invalid("승인 또는 반려를 선택해 주세요.");
        };
        String reason = "REJECTED".equals(state) ? input.rejectionReason() : null;
        if ("REJECTED".equals(state) && (reason == null || reason.isBlank() || reason.length() > 10000)) throw invalid("반려 사유가 필요합니다.");
        if (!"SUBMITTED".equals(row.get("status"))) {
            if (state.equals(row.get("status")) && Objects.equals(reason, row.get("rejection_reason"))) return submissionView(row);
            throw conflict("submission_decided");
        }
        initialWorkflow(project);
        var copy = copy(project, document);
        if (!"SUBMITTED".equals(copy.get("status")) || id(copy, "revision") != id(row, "revision")) throw conflict("version_conflict");
        repository.decide(row, caller.accountId(), state, reason);
        // [TODO] 전진님 공통 알림 서비스 계약 확정 후 등록자 승인·반려 알림 로직 넣기. 제출 ID·결정·반려 사유를 전달한다.
        return submissionView(repository.submission(project, document, submission).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<DocumentSubmissionResponse> submissions(CallerContext caller, long project, String status) {
        member(caller, project);
        if (status != null && !Set.of("SUBMITTED", "APPROVED", "REJECTED").contains(status)) throw invalid("검토 상태가 올바르지 않습니다.");
        return repository.submissions(project, null, status).stream().map(this::submissionView).toList();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public DocumentSubmissionDetailResponse submission(CallerContext caller, long project, long document, long submission) {
        member(caller, project);
        var row = repository.submission(project, document, submission).orElseThrow(() -> AccessErrors.notFound("document_submission_missing"));
        return new DocumentSubmissionDetailResponse(submissionView(row), repository.submittedContent(id(row, "document_version_id")),
                reviewView(repository.review(document, id(row, "bee_review_id")).orElseThrow()));
    }

    private DocumentWorkingCopyResponse view(Map<String, Object> row) {
        long document = id(row, "document_id");
        return new DocumentWorkingCopyResponse(Long.toString(document), row.get("owner_account_id").toString(), id(row, "revision"),
                row.get("status").toString(), row.get("content_text").toString(), row.get("file_name").toString(),
                repository.review(document, null).map(this::reviewView).orElse(null),
                repository.submissions(id(row, "project_id"), document, null).stream().map(this::submissionView).toList());
    }

    @SuppressWarnings("unchecked")
    private DocumentBeeReviewResponse reviewView(Map<String, Object> row) {
        var result = object(row.get("result"));
        var input = object(row.get("input"));
        var refs = (List<Map<String, Object>>) input.get("references");
        long review = id(row, "id");
        return new DocumentBeeReviewResponse(Long.toString(review), id(row, "input_revision"), id(row, "resolved_revision"),
                row.get("reference_hash").toString(), result.get("summary").toString(), (List<String>) result.get("unavailable_references"),
                refs.stream().map(r -> new DocumentReviewReferenceResponse(r.get("document_id").toString(),
                        r.get("document_version_id").toString(), r.get("kind").toString(), ((Number) r.get("version_no")).intValue(), r.get("content").toString())).toList(),
                repository.suggestions(review).stream().map(s -> new DocumentSuggestionResponse(s.get("id").toString(),
                        s.get("explanation").toString(), s.get("original_text").toString(), s.get("replacement_text").toString(), s.get("decision").toString())).toList(),
                JSON.readValue(row.get("usage").toString(), List.class));
    }

    private DocumentSubmissionResponse submissionView(Map<String, Object> row) {
        return new DocumentSubmissionResponse(row.get("id").toString(), row.get("document_id").toString(), row.get("document_version_id").toString(),
                row.get("submitted_by").toString(), id(row, "revision"), row.get("status").toString(), row.get("content_hash").toString(),
                time(row.get("submitted_at")), row.get("decided_by") == null ? null : row.get("decided_by").toString(),
                row.get("decided_at") == null ? null : time(row.get("decided_at")), (String) row.get("rejection_reason"));
    }
}
