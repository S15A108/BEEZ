package com.beez.backend.document.service;

import static com.beez.backend.designitem.support.DesignJson.*;

import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityTargetType;
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
import com.beez.backend.document.validation.DocumentReviewReferenceHasher;
import com.beez.backend.document.validation.DocumentReviewResultValidator;
import com.beez.backend.notification.NotificationRequest;
import com.beez.backend.notification.NotificationRequester;
import com.beez.backend.notification.NotificationTargetType;
import com.beez.backend.notification.event.NotificationMessages;
import com.beez.backend.notification.event.NotificationMessages.Message;
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
    private final NotificationRequester notifier;
    private final ActivityRecorder activities;
    /** 워커 한 번의 lease. AI 읽기 제한보다 1분 길어 정상 처리 중에는 다른 워커가 이어받지 않는다. */
    private final int leaseSeconds;
    /** lease 가 지난 작업(배포 재시작 등)을 다시 잡는 것까지 합친 최대 시도 횟수. 두 번째도 못 끝내면 시간 초과로 닫는다. */
    static final int MAX_ATTEMPTS = 2;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DocumentReviewService.class);

    public DocumentReviewService(DocumentReviewRepository repository, ProjectAccessChecker access,
            DocumentInputValidator validator, DocumentReviewGateway ai, DocumentStorage storage,
            PlatformTransactionManager manager, NotificationRequester notifier, ActivityRecorder activities,
            @org.springframework.beans.factory.annotation.Value("${beez.document-review.worker-read-timeout:120s}") java.time.Duration workerReadTimeout) {
        this.leaseSeconds = (int) workerReadTimeout.plusMinutes(1).toSeconds();
        this.notifier = notifier;
        this.activities = activities;
        this.repository = repository; this.access = access; this.validator = validator; this.ai = ai;
        tx = new TransactionTemplate(manager); originals = new DocumentOriginalWriter(storage, manager); this.storage = storage;
    }

    /** 문서 알림 한 건. 대상은 문서 버전이고 화면이 문서 상세 주소를 만들도록 documentId 를 함께 보낸다 */
    private void notifyDocument(long project, long document, long version, long recipient, String keyBase, String type, Message message) {
        notifier.request(new NotificationRequest(keyBase + ":to:" + recipient, project, recipient, type, message.title(),
                message.body(), NotificationTargetType.DOCUMENT_VERSION, version, document));
    }

    /** 작성본의 기준이 되는 가장 최근 문서 버전. 아직 승인 전이면 처음 만든 초안 버전이다 */
    private long latestVersion(Map<String, Object> copy) {
        return id(copy, copy.get("base_version_id") != null ? "base_version_id" : "initial_version_id");
    }

    private void member(CallerContext caller, long project) { access.requireMember(caller, project).requireHuman(); }

    void lock(CallerContext caller, long project, boolean leader) {
        if (leader) access.requireHumanLeader(caller, project); else member(caller, project);
        if (!repository.lockMember(project, caller.accountId(), leader)) {
            if (leader && repository.currentRole(project, caller.accountId()).isPresent()) {
                throw AccessErrors.forbidden("document_leader_changed", "현재 사람 팀장만 심사할 수 있습니다.");
            }
            throw AccessErrors.notFound("document_review_membership_changed");
        }
    }

    void initialWorkflow(long project) {
        if (repository.hasBaseline(project)) throw conflict("change_proposal_required");
    }

    Map<String, Object> copy(long project, long document) {
        return repository.workingCopy(project, document).orElseThrow(() -> AccessErrors.notFound("document_working_copy_missing"));
    }

    Map<String, Object> owned(CallerContext caller, long project, long document) { return owned(caller.accountId(), project, document); }

    /** 워커처럼 호출자 문맥이 없을 때도 같은 규칙으로 등록자를 확인한다. */
    Map<String, Object> owned(long account, long project, long document) {
        var copy = copy(project, document);
        if (id(copy, "owner_account_id") != account) {
            throw AccessErrors.forbidden("document_author_required", "등록한 담당자만 작성 내용을 변경할 수 있습니다.");
        }
        return copy;
    }

    void editable(Map<String, Object> copy, long expected) {
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

    List<Map<String, Object>> references(long project, long document) { return repository.references(project, document); }

    String referenceHash(List<Map<String, Object>> references) {
        return DocumentReviewReferenceHasher.referenceHash(DocumentReviewReferenceHasher.snapshots(references));
    }

    Map<String, Object> reviewInput(Map<String, Object> copy, List<Map<String, Object>> refs, String key) {
        var available = refs.stream().map(r -> r.get("type").toString()).toList();
        var missing = List.of("API_SPEC", "ERD", "REQ_SPEC").stream()
                .filter(type -> !type.equals(copy.get("type")) && !available.contains(type)).toList();
        var input = new LinkedHashMap<String, Object>();
        input.put("contract_version", 2); input.put("project_id", copy.get("project_id").toString());
        input.put("document_id", copy.get("document_id").toString()); input.put("revision", copy.get("revision"));
        input.put("content", copy.get("content_text")); input.put("kind", copy.get("type"));
        input.put("content_hash", DocumentReviewReferenceHasher.textHash(copy.get("content_text").toString()));
        input.put("request_key", key); input.put("reference_hash", referenceHash(refs));
        input.put("unavailable_references", missing); input.put("references", DocumentReviewReferenceHasher.snapshots(refs));
        return input;
    }

    static String reviewFingerprint(long expected, String referenceHash, Object contentHash, Map<String, Object> context) {
        var components = new ArrayList<Object>(List.of("document-review-v2", expected, referenceHash, contentHash));
        if (!context.isEmpty()) {
            components.add(context.get("mode"));
            components.add(context.get("rejection_submission_id"));
            components.add(context.get("rejection_reason"));
        }
        return hash(components);
    }

    void key(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Idempotency-Key는 제어 문자가 없는 1~128자입니다.");
        }
    }

    /**
     * DOC-A16 비비 검토 접수(S15P31A108-391). 권한·리비전·멱등을 확인하고 AI 에 보낼 입력을 저장한 뒤 바로 돌아온다.
     * 검토는 {@link #runNextReview()} 를 부르는 워커가 HTTP 요청 밖에서 한다(nginx 60초에 묶이지 않는다).
     * 같은 키로 이미 끝난 검토는 결과를, 아직 도는 검토는 요청 상태를 돌려준다. 같은 본문·참고 자료로 도는 검토가 있으면
     * 새 키여도 새로 만들지 않고 그 요청을 기다리게 한다(AI 를 두 번 부르지 않는다).
     */
    @Transactional(propagation = Propagation.NEVER)
    public DocumentReviewAcceptance review(CallerContext caller, long project, long document, long expected, String key) {
        key(key);
        return tx.execute(status -> {
            lock(caller, project, false);
            var copy = owned(caller, project, document);
            var refs = references(project, document);
            var reviewContext = ReviewRerunPolicy.context(repository.latestSubmission(project, document).stream().toList());
            String fingerprint = reviewFingerprint(expected, referenceHash(refs),
                    DocumentReviewReferenceHasher.textHash(copy.get("content_text").toString()), reviewContext);
            var previous = repository.reviewRequest(document, caller.accountId(), key);
            if (previous.isPresent()) {
                var old = previous.get();
                // Completed pre-v2 keys still replay their original result without inventing v2 stages.
                String legacyFingerprint = hash(List.of(expected, hash(refs.stream()
                        .map(r -> List.of(r.get("document_id"), r.get("document_version_id"), r.get("content_hash"))).toList())));
                boolean completedReplay = false;
                if ("COMPLETED".equals(old.get("status"))) {
                    var original = object(repository.review(document, id(old, "review_id")).orElseThrow().get("input"));
                    // Applying a PATCH changes the working copy, not the input of a completed request.
                    completedReplay = original.get("content_hash") == null
                            ? legacyFingerprint.equals(old.get("fingerprint"))
                            : reviewFingerprint(expected, referenceHash(refs), original.get("content_hash"), ReviewRerunPolicy.fromInput(original))
                                    .equals(old.get("fingerprint"));
                }
                if (!fingerprint.equals(old.get("fingerprint")) && !completedReplay) throw conflict("idempotency_conflict");
                if ("COMPLETED".equals(old.get("status"))) {
                    return DocumentReviewAcceptance.completed(reviewView(repository.review(document, id(old, "review_id")).orElseThrow()));
                }
                if ("RUNNING".equals(old.get("status"))) return DocumentReviewAcceptance.running(requestView(old));
                if ("review_timeout".equals(old.get("error_code"))) throw timeout();
                throw unavailable("이 검토 요청은 실패했습니다. 새 요청 키로 다시 검토해 주세요.");
            }
            editable(copy, expected);
            initialWorkflow(project);
            var running = repository.runningReview(document, caller.accountId(), fingerprint);
            if (running.isPresent()) return DocumentReviewAcceptance.running(requestView(running.get()));
            // request_key 는 워커가 AI 를 부를 때마다 새로 붙인다(재시도한 호출도 사용량에 따로 센다).
            var input = reviewInput(copy, refs, null);
            input.remove("request_key");
            input.putAll(reviewContext);
            repository.startReview(project, document, caller.accountId(), key, fingerprint, expected, input);
            return DocumentReviewAcceptance.running(requestView(repository.reviewRequest(document, caller.accountId(), key).orElseThrow()));
        });
    }

    /**
     * 서버 종료로 워커 스레드가 interrupt 되어 끊긴 호출인지. JDK HttpClient 는 interrupt 를 IOException 으로 감싸 던지고
     * 플래그를 다시 세우지만, 감싸는 방식이 바뀌어도 알아보도록 원인 사슬도 본다.
     */
    static boolean interrupted(Throwable failure) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof java.io.InterruptedIOException
                    && !(cause instanceof java.net.SocketTimeoutException) || cause instanceof java.nio.channels.ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    /** 워커가 잡은 작업이 다른 워커에게 넘어갔을 때(lease 만료 뒤 이어받음). 저장하지 않고 조용히 손을 뗀다. */
    private static final class LeaseLost extends RuntimeException {
        LeaseLost() { super(null, null, false, false); }
    }

    /**
     * 접수된 검토 하나를 끝까지 처리한다(S15P31A108-391). 처리할 작업이 없으면 false.
     *
     * <p>AI 호출 동안 트랜잭션·프로젝트 잠금을 잡지 않는다. 저장할 때 프로젝트 잠금을 잡고 요청자가 아직 멤버·등록자인지, 본문 리비전·참고
     * 승인본·반려 문맥이 접수 때와 같은지, 작업이 아직 내 것인지(울타리 토큰) 다시 확인한다. 실패는 작업만 FAILED 로 남긴다(동기 호출 때와 같은
     * 오류 코드). lease 가 지나면(배포 재시작 등) 다른 워커가 한 번 더 시도하고, 두 번째도 끝내지 못하면 시간 초과로 닫는다.
     */
    @Transactional(propagation = Propagation.NEVER)
    public boolean runNextReview() {
        var job = tx.execute(status -> repository.claimReview(leaseSeconds, MAX_ATTEMPTS).orElse(null));
        if (job == null) return false;
        long project = id(job, "project_id"), document = id(job, "document_id"), account = id(job, "account_id");
        long expected = id(job, "expected_revision");
        String key = job.get("request_key").toString();
        Object token = job.get("lease_token");
        var prepared = new HashMap<String, Object>(object(job.get("input")));
        prepared.put("request_key", "document-review-" + document + "-" + UUID.randomUUID());
        List<Map<String, Object>> usage = List.of();
        try {
            // Network and model work never hold a project lock or DB transaction.
            // [TODO] 216-AI: 양지훈 222의 v2 계약 실연동 확인. 현재 검증은 Gateway 대역이다.
            var envelope = ai.review(prepared);
            usage = DocumentReviewUsageValidator.extract(envelope == null ? null : envelope.get("usage"), prepared.get("request_key").toString());
            if (envelope != null) { envelope = new HashMap<>(envelope); envelope.put("usage", usage); }
            var result = validateResult(envelope, prepared);
            var actualUsage = usage;
            tx.executeWithoutResult(status -> {
                if (!repository.lockMember(project, account, false)) throw AccessErrors.notFound("document_review_membership_changed");
                if (!repository.reviewLeaseHeld(document, account, key, token)) throw new LeaseLost();
                var copy = owned(account, project, document);
                editable(copy, expected);
                initialWorkflow(project);
                if (!prepared.get("reference_hash").equals(referenceHash(references(project, document)))) throw conflict("review_stale");
                if (!ReviewRerunPolicy.fromInput(prepared).equals(ReviewRerunPolicy.context(repository.latestSubmission(project, document).stream().toList()))) {
                    throw conflict("review_stale");
                }
                long review = repository.saveReview(document, expected, prepared.get("reference_hash").toString(), prepared, result, actualUsage);
                for (Object entry : (List<?>) result.get("suggestions")) {
                    var suggestion = (Map<?, ?>) entry;
                    repository.saveSuggestion(review, suggestion);
                }
                if (!repository.finishReview(document, account, key, token, review, null, actualUsage)) throw new LeaseLost();
                repository.recordUsage(project, actualUsage);
                // 새 검토를 저장한 이 트랜잭션에서만 알린다. 행위자가 비비라서 요청한 등록자 본인도 받는다(계약 6.1).
                // 비동기라 요청자가 화면을 떠났어도 이 알림으로 끝난 것을 안다.
                var done = NotificationMessages.documentBeeReviewDone(copy.get("title").toString());
                notifyDocument(project, document, latestVersion(copy), account,
                        "document-review:" + review + ":bee-review-done", "DOCUMENT_BEE_REVIEW_DONE", done);
            });
        } catch (LeaseLost lost) {
            // 이어받은 워커가 끝낸다. 이 호출의 사용량만 남긴다(실제로 일어난 호출이다).
            var actualUsage = usage;
            tx.executeWithoutResult(status -> repository.recordUsage(project, actualUsage));
        } catch (RuntimeException failure) {
            if (interrupted(failure)) {
                // 서버 종료(배포 재시작)로 끊겼다. 실패로 남기지 않고 lease 가 지나면 다른 워커가 이어서 하게 둔다.
                // 이 호출의 사용량은 알 수 없다(모델 응답을 받지 못했다).
                log.info("문서 검토 워커가 종료로 중단됨 document={} 재시도 대기", document);
                return true;
            }
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
            // 시간 초과는 장애(review_unavailable)와 구분해 review_timeout 으로 남긴다. 사용량 기록은 둘 다 같다(모델이 돌았을 수 있다).
            String errorCode = failure instanceof ApiException e ? e.getCode() : isTimeout(failure) ? "review_timeout" : "review_unavailable";
            if (!(failure instanceof ApiException)) log.warn("문서 검토 AI 호출 실패 document={} 예외 종류={}", document, failure.getClass().getSimpleName());
            tx.executeWithoutResult(status -> {
                repository.finishReview(document, account, key, token, null, errorCode, actualUsage);
                repository.recordUsage(project, actualUsage);
            });
        }
        return true;
    }

    private Map<String, Object> validateResult(Map<String, Object> envelope, Map<String, Object> input) {
        return DocumentReviewResultValidator.validate(envelope, input);
    }
    ApiException timeout() {
        return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "review_timeout",
                "비비가 문서를 검토하는 데 시간이 너무 오래 걸렸어요. 문서가 길 때 생길 수 있어요.");
    }

    /**
     * 모델 시간 초과(AI 504·model_timeout)와 BE 읽기 시간 초과만 true. 연결 시간 초과는 AI 에 닿지 못한 장애라 false.
     * AssignmentSuggestionService 와 같은 구분이다.
     */
    static boolean isTimeout(Throwable failure) {
        if (failure instanceof org.springframework.web.client.RestClientResponseException response) {
            if (response.getStatusCode().value() == 504) return true;
            try {
                var body = JSON.readValue(response.getResponseBodyAsString(), Map.class);
                return body.get("error") instanceof Map<?, ?> error && "model_timeout".equals(error.get("code"));
            } catch (RuntimeException ignored) { return false; }
        }
        if (failure instanceof org.springframework.web.client.ResourceAccessException) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof java.net.http.HttpConnectTimeoutException) return false;
                if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) return true;
            }
        }
        return false;
    }

    ApiException unavailable(String message) { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "review_unavailable", message); }

    Map<String, Object> currentReview(long project, long document, long review, long revision) {
        var row = repository.review(document, review).orElseThrow(() -> AccessErrors.notFound("document_review_missing"));
        if (!usable(row, project, document, revision)) throw conflict("review_stale");
        return row;
    }

    /** 검토를 지금 쓸 수 있는지. 쓰기 API의 review_stale 판정과 조회의 review_stale 표시가 같은 규칙을 쓴다. */
    boolean usable(Map<String, Object> review, long project, long document, long revision) {
        return Integer.valueOf(2).equals(object(review.get("result")).get("contract_version"))
                && id(review, "resolved_revision") == revision
                && review.get("reference_hash").equals(referenceHash(references(project, document)));
    }

    /** 화면에는 문서의 최신 검토만 보인다. 같은 본문으로 재검토가 끝났으면 이전 검토에 대화·요청을 남기지 않는다. */
    void requireLatestReview(long document, long review) {
        var latest = repository.review(document, null).orElseThrow(() -> AccessErrors.notFound("document_review_missing"));
        if (id(latest, "id") != review) throw conflict("review_stale");
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
        if (!"PATCH".equals(row.get("type"))) throw invalid("설명형 의견은 반영하거나 넘기는 대상이 아닙니다.");
        String decision = "APPLY".equals(input.decision()) ? "APPLIED" : "SKIPPED";
        if (!Set.of("APPLY", "SKIP").contains(input.decision())) throw invalid("제안 처리 방식이 올바르지 않습니다.");
        // DOC-A22: 대화에서 비비가 낸 다른 대체문으로 반영할 때만 replacement_text 를 받는다(SKIP 에는 보낼 수 없다).
        String chosen = input.replacementText();
        if (chosen != null) {
            if (!"APPLY".equals(input.decision())) throw invalid("대체문은 반영할 때만 보낼 수 있습니다.");
            if (repository.messagesOf(suggestion).stream().noneMatch(m -> "BEE".equals(m.get("author_kind")) && chosen.equals(m.get("proposed_text")))) {
                throw invalid("대화에서 비비가 낸 대체문만 반영할 수 있습니다.");
            }
        }
        if (!"PENDING".equals(row.get("decision"))) {
            if (!decision.equals(row.get("decision"))) throw conflict("version_conflict");
            if (chosen != null && !chosen.equals(row.get("applied_replacement_text") != null ? row.get("applied_replacement_text") : row.get("replacement_text"))) {
                throw conflict("version_conflict");
            }
            return view(copy);
        }
        long revision = input.expectedRevision();
        if ("APPLIED".equals(decision)) {
            String content = copy.get("content_text").toString(), original = row.get("original_text").toString();
            int start = content.indexOf(original);
            if (start < 0 || start != content.lastIndexOf(original)) throw conflict("review_stale");
            String replacement = chosen != null ? chosen : row.get("replacement_text").toString();
            String updated = content.substring(0, start) + replacement + content.substring(start + original.length());
            var source = validator.validate(copy.get("file_name").toString(), updated.getBytes(StandardCharsets.UTF_8));
            repository.edit(document, source.text(), copy.get("file_name").toString());
            revision++;
        }
        repository.decideSuggestion(suggestion, decision, review, revision, "APPLIED".equals(decision) ? chosen : null);
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
            var saved = repository.submission(project, document, submission).orElseThrow();
            // 위의 replay 분기는 이미 반환했으므로 새 제출에서만 팀장에게 알린다. 팀장이 직접 제출하면 본인은 받지 않는다.
            var requested = NotificationMessages.documentReviewRequested(copy.get("title").toString());
            repository.leaderAccount(project).filter(leader -> !leader.equals(caller.accountId()))
                    .ifPresent(leader -> notifyDocument(project, document, id(saved, "document_version_id"), leader,
                            "doc-submission:" + submission + ":review-requested", "DOCUMENT_REVIEW_REQUESTED", requested));
            var response = submissionView(saved);
            repository.remember(project, caller.accountId(), operation, key, fingerprint, response);
            return response;
        });
    }

    private void validateSubmission(long project, long document, Map<String, Object> copy, long revision, long review) {
        initialWorkflow(project);
        editable(copy, revision);
        if ("APPROVED".equals(copy.get("status"))) throw conflict("version_conflict");
        var row = currentReview(project, document, review, revision);
        var result = object(row.get("result"));
        if (!Set.of("COMPLETE", "PARTIAL").contains(result.get("review_status"))) throw conflict("review_stale");
        if (repository.suggestions(review).stream().anyMatch(s -> "PATCH".equals(s.get("type"))
                && "PENDING".equals(s.get("decision")))) throw conflict("review_decisions_pending");
    }

    /**
     * DOC-A24 팀장이 결정하기 전에 제출을 거두고 작성 내용을 다시 고칠 수 있게 한다. 제출·검토·대화·결정 이력은 지우지 않고
     * 제출은 WITHDRAWN 으로 종결한다. 작성 사본은 EDITING 으로 돌아가며 revision 이 올라 이전 검토는 오래된 것이 되어(반려 뒤 재작성과 같다)
     * 고친 뒤 비비 검토를 다시 받아 재제출한다. 팀장 결정(decide)과 같은 프로젝트 잠금을 잡아 둘 중 하나만 성립한다.
     */
    @Transactional
    public DocumentWorkingCopyResponse withdraw(CallerContext caller, long project, long document, long submission, String key) {
        key(key);
        lock(caller, project, false);
        var copy = owned(caller, project, document);
        String operation = "withdraw-document:" + submission, fingerprint = hash(Map.of("submission", submission));
        var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
        if (previous.isPresent()) return JSON.readValue(previous.get(), DocumentWorkingCopyResponse.class);
        var row = repository.submission(project, document, submission).orElseThrow(() -> AccessErrors.notFound("document_submission_missing"));
        if (id(row, "submitted_by") != caller.accountId()) {
            throw AccessErrors.forbidden("document_author_required", "제출한 담당자만 검토 요청을 취소할 수 있습니다.");
        }
        String status = row.get("status").toString();
        if (!"SUBMITTED".equals(status) && !"WITHDRAWN".equals(status)) throw conflict("submission_decided");
        if ("SUBMITTED".equals(status)) {
            initialWorkflow(project);
            if (!repository.withdraw(submission, caller.accountId())) throw conflict("submission_decided");
            repository.reopenAfterWithdraw(document, id(row, "document_version_id"));
        }
        var response = view(copy(project, document));
        repository.remember(project, caller.accountId(), operation, key, fingerprint, response);
        return response;
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
        if ("WITHDRAWN".equals(row.get("status"))) throw conflict("submission_withdrawn");
        if (!"SUBMITTED".equals(row.get("status"))) {
            if (state.equals(row.get("status")) && Objects.equals(reason, row.get("rejection_reason"))) return submissionView(row);
            throw conflict("submission_decided");
        }
        initialWorkflow(project);
        var copy = copy(project, document);
        if (!"SUBMITTED".equals(copy.get("status")) || id(copy, "revision") != id(row, "revision")) throw conflict("version_conflict");
        repository.decide(row, caller.accountId(), state, reason);
        // 이미 결정된 제출의 재요청은 위에서 반환했다. 등록자에게만 알리고(본인이 결정한 경우 제외) 반려 사유는 알림에 넣지 않는다.
        long registrant = id(row, "submitted_by");
        if (registrant != caller.accountId()) {
            boolean approved = "APPROVED".equals(state);
            String documentTitle = copy.get("title").toString();
            notifyDocument(project, document, id(row, "document_version_id"), registrant,
                    "doc-submission:" + submission + (approved ? ":approved" : ":rejected"),
                    approved ? "DOCUMENT_APPROVED" : "DOCUMENT_REJECTED",
                    approved ? NotificationMessages.documentApproved(documentTitle) : NotificationMessages.documentRejected(documentTitle));
        }
        // 활동은 알림과 달리 등록자 본인이 결정해도 남긴다. 반려 사유는 넣지 않는다(타임라인은 멤버 전원이 본다).
        activities.record(caller, ActivityEvent.of("doc-submission:" + submission + ("APPROVED".equals(state) ? ":approved" : ":rejected"),
                project, "APPROVED".equals(state) ? "DOCUMENT_APPROVED" : "DOCUMENT_REJECTED",
                ActivityTargetType.DOCUMENT_VERSION, id(row, "document_version_id")));
        return submissionView(repository.submission(project, document, submission).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<DocumentSubmissionResponse> submissions(CallerContext caller, long project, String status) {
        member(caller, project);
        if (status != null && !Set.of("SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN").contains(status)) throw invalid("검토 상태가 올바르지 않습니다.");
        return repository.submissions(project, null, status).stream().map(this::submissionView).toList();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public DocumentSubmissionDetailResponse submission(CallerContext caller, long project, long document, long submission) {
        member(caller, project);
        var row = repository.submission(project, document, submission).orElseThrow(() -> AccessErrors.notFound("document_submission_missing"));
        return new DocumentSubmissionDetailResponse(submissionView(row), repository.submittedContent(id(row, "document_version_id")),
                reviewView(repository.review(document, id(row, "bee_review_id")).orElseThrow()));
    }

    DocumentWorkingCopyResponse view(Map<String, Object> row) {
        long document = id(row, "document_id"), project = id(row, "project_id");
        var latest = repository.review(document, null);
        // 팀장 결정을 기다리거나 끝난 작성 건은 제출 때 이미 통과한 검토라 무효로 표시하지 않는다.
        boolean open = !Set.of("SUBMITTED", "APPROVED").contains(row.get("status").toString());
        boolean stale = open && latest.isPresent() && !usable(latest.get(), project, document, id(row, "revision"));
        return new DocumentWorkingCopyResponse(Long.toString(document), row.get("owner_account_id").toString(), id(row, "revision"),
                row.get("status").toString(), row.get("content_text").toString(), row.get("file_name").toString(),
                latest.map(this::reviewView).orElse(null),
                repository.submissions(project, document, null).stream().map(this::submissionView).toList(), stale,
                repository.latestReviewRequest(document).map(this::requestView).orElse(null));
    }

    /**
     * 도는 중으로 {@link DocumentReviewRepository#STALE_MINUTES}분이 넘은 요청은 서버가 같은 키 요청 때 실패 처리하는 것과 같게
     * 실패로 보여, 팀원 화면이 끝없이 기다리지 않게 한다. 워커가 지금 붙잡고 있는(lease 가 살아 있는) 요청은 끝날 때까지 도는 중이다.
     */
    BeeReviewRequestResponse requestView(Map<String, Object> row) {
        String status = row.get("status").toString(), error = (String) row.get("error_code");
        String created = DocumentValues.time(row.get("created_at"));
        String at = row.get("updated_at") == null ? created : DocumentValues.time(row.get("updated_at"));
        var now = java.time.Instant.now();
        boolean held = row.get("lease_until") != null && java.time.Instant.parse(DocumentValues.time(row.get("lease_until"))).isAfter(now);
        if ("RUNNING".equals(status) && !held
                && java.time.Instant.parse(created).isBefore(now.minus(java.time.Duration.ofMinutes(DocumentReviewRepository.STALE_MINUTES)))) {
            status = "FAILED"; error = "review_timeout";
        }
        return new BeeReviewRequestResponse(status, error, at);
    }

    /**
     * DOC-A25 문서 등록 취소. 등록자나 팀장이, 승인된 적 없는 문서를 기준 설계 v1 전에 취소한다.
     * 삭제하지 않고 폐기 시각을 남기며(버전은 미채택) 같은 종류의 문서를 다시 올릴 수 있다. 같은 문서를 다시 취소하면 같은 결과다.
     */
    @Transactional
    public DocumentDiscardResponse discard(CallerContext caller, long project, long document, String key) {
        key(key);
        lock(caller, project, false);
        var copy = repository.workingCopy(project, document, true)
                .orElseThrow(() -> AccessErrors.notFound("document_working_copy_missing"));
        boolean leader = "LEADER".equals(repository.currentRole(project, caller.accountId()).orElse(""));
        if (id(copy, "owner_account_id") != caller.accountId() && !leader) {
            throw AccessErrors.forbidden("document_author_required", "등록한 담당자나 팀장만 문서 등록을 취소할 수 있습니다.");
        }
        String operation = "discard-document:" + document, fingerprint = hash(Map.of("document", document));
        var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
        if (previous.isPresent()) return JSON.readValue(previous.get(), DocumentDiscardResponse.class);
        if (copy.get("discarded_at") == null) {
            if ("SUBMITTED".equals(copy.get("status"))) throw conflict("submission_locked");
            if ("APPROVED".equals(copy.get("status")) || repository.hasApprovedVersion(document)) throw conflict("document_approved");
            initialWorkflow(project);
            repository.discard(document);
            activities.record(caller, ActivityEvent.of("doc-discard:" + document, project, "DOCUMENT_DISCARDED",
                    ActivityTargetType.DOCUMENT_VERSION, latestVersion(copy)));
        }
        var response = new DocumentDiscardResponse(Long.toString(document), true);
        repository.remember(project, caller.accountId(), operation, key, fingerprint, response);
        return response;
    }

    @SuppressWarnings("unchecked")
    DocumentBeeReviewResponse reviewView(Map<String, Object> row) {
        var result = object(row.get("result"));
        var input = object(row.get("input"));
        var refs = (List<Map<String, Object>>) input.get("references");
        long review = id(row, "id");
        return new DocumentBeeReviewResponse(Long.toString(review), id(row, "input_revision"), id(row, "resolved_revision"),
                row.get("reference_hash").toString(), result.get("summary").toString(), (List<String>) result.get("unavailable_references"),
                refs.stream().map(r -> new DocumentReviewReferenceResponse(r.get("document_id").toString(),
                        r.get("document_version_id").toString(), r.get("kind").toString(), ((Number) r.get("version_no")).intValue(),
                        r.get("content").toString(), (String) r.get("reference_id"), (String) r.get("status"), (String) r.get("content_hash"))).toList(),
                suggestionViews(review),
                JSON.readValue(row.get("usage").toString(), List.class),
                (Integer) result.get("contract_version"), (String) result.get("content_hash"), (String) result.get("review_status"),
                result.get("steps") == null ? List.of() : Arrays.asList(JSON.convertValue(result.get("steps"), DocumentReviewStepResponse[].class)),
                result.get("references_used") == null ? List.of() : (List<String>) result.get("references_used"));
    }

    /** 검토의 모든 보완 제안. 출처(origin)와 제안별 대화(DOC-A22)를 함께 담는다. */
    List<DocumentSuggestionResponse> suggestionViews(long review) {
        var byId = new LinkedHashMap<Long, List<DocumentMessageResponse>>();
        for (var m : repository.messages(review)) byId.computeIfAbsent(id(m, "suggestion_id"), k -> new ArrayList<>()).add(messageView(m));
        return repository.suggestions(review).stream().map(s -> suggestionView(s, byId.getOrDefault(id(s, "id"), List.of()))).toList();
    }

    DocumentSuggestionResponse suggestionView(Map<String, Object> s, List<DocumentMessageResponse> messages) {
        return new DocumentSuggestionResponse(s.get("id").toString(), s.get("explanation").toString(),
                (String) s.get("original_text"), (String) s.get("replacement_text"), (String) s.get("decision"),
                s.get("type").toString(), (String) s.get("step"),
                Arrays.asList(JSON.readValue(s.get("sources").toString(), DocumentReviewSourceResponse[].class)),
                (String) s.get("origin"), (String) s.get("applied_replacement_text"), messages,
                (String) s.get("category"), (String) s.get("severity"));
    }

    static DocumentMessageResponse messageView(Map<String, Object> m) {
        return new DocumentMessageResponse(m.get("id").toString(), m.get("author_kind").toString(), m.get("body").toString(),
                (String) m.get("proposed_text"), time(m.get("created_at")));
    }

    private DocumentSubmissionResponse submissionView(Map<String, Object> row) {
        return new DocumentSubmissionResponse(row.get("id").toString(), row.get("document_id").toString(), row.get("document_version_id").toString(),
                row.get("submitted_by").toString(), id(row, "revision"), row.get("status").toString(), row.get("content_hash").toString(),
                time(row.get("submitted_at")), row.get("decided_by") == null ? null : row.get("decided_by").toString(),
                row.get("decided_at") == null ? null : time(row.get("decided_at")), (String) row.get("rejection_reason"));
    }
}
