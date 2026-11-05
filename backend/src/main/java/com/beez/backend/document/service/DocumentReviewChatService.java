package com.beez.backend.document.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.conflict;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.id;
import static com.beez.backend.designitem.support.DesignJson.invalid;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.dto.ReviewChatDtos.DirectRequest;
import com.beez.backend.document.dto.ReviewChatDtos.DirectResponse;
import com.beez.backend.document.dto.ReviewChatDtos.MessageRequest;
import com.beez.backend.document.dto.ReviewChatDtos.MessageResponse;
import com.beez.backend.document.port.DocumentReviewGateway;
import com.beez.backend.document.repository.DocumentReviewRepository;
import com.beez.backend.document.validation.DocumentReviewResultValidator;
import com.beez.backend.document.validation.DocumentReviewUsageValidator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DOC-A22 보완 제안에 대해 비비와 대화, DOC-A23 비비에게 직접 보완 요청.
 *
 * <p>둘 다 등록자만, 지금 작성 내용의 최신 검토에서만 쓸 수 있고(직접 수정·참고 승인본 변경 후에는 {@code review_stale},
 * 팀장 검토 요청 중에는 {@code submission_locked}), AI 호출 동안 트랜잭션·프로젝트 잠금을 잡지 않는다. AI 가 돌아오면 권한·리비전·참고 해시를
 * 다시 확인하고 그때까지 같을 때만 저장한다 — 호출 중에 본문이 바뀌었다면 오래된 응답은 거절한다.
 *
 * <p><b>대화는 본문을 바꾸지 않는다.</b> 비비가 다른 대체문을 내도 메시지에 담길 뿐이며, 반영은 DOC-A17 APPLY 에 그 문장을
 * {@code replacement_text} 로 보낼 때 서버가 대화 기록과 정확히 같은지 확인한 뒤 한다. 직접 요청은 검토 결과({@code result})와 참고 해시를
 * 바꾸지 않고 같은 검토에 PENDING PATCH 제안 행만 더한다.
 *
 * <p>AI-A10 {@code mode=DISCUSS|REQUEST} 계약은 AI 담당과 합의 전 제안 상태다. 모델 실패·시간 초과·형식 위반은 기존 검토 결과를 건드리지
 * 않고 오류로만 돌려준다(템플릿으로 대신하지 않는다). 같은 키의 완료된 요청은 AI 를 다시 부르지 않고 저장된 응답을 재생한다.
 * 같은 키가 동시에 둘 들어오면 둘 다 AI 를 부를 수 있지만 저장은 한 번만 된다(나중 쪽이 저장된 응답을 받는다).
 */
@Service
public class DocumentReviewChatService {
    static final int MAX_DIRECT_REQUESTS = 20;
    static final int MAX_MESSAGES_SENT = 40;
    /** 한 검토에서 비비에게 보낼 수 있는 대화 횟수(제안 전체 합계). AI 비용 상한이다. */
    static final int MAX_DISCUSSIONS = 80;

    private final DocumentReviewService reviews;
    private final DocumentReviewRepository repository;
    private final DocumentReviewGateway ai;
    private final TransactionTemplate tx;

    public DocumentReviewChatService(DocumentReviewService reviews, DocumentReviewRepository repository, DocumentReviewGateway ai,
                                     PlatformTransactionManager manager) {
        this.reviews = reviews; this.repository = repository; this.ai = ai; this.tx = new TransactionTemplate(manager);
    }

    /** AI 호출 전에 확인한 값. replay 가 있으면 저장된 응답을 그대로 돌려준다. */
    private record Prepared(String replay, Map<String, Object> payload, long revision, String referenceHash, Map<String, Object> reviewResult) { }

    // ---- DOC-A22 ----

    @Transactional(propagation = Propagation.NEVER)
    public MessageResponse discuss(CallerContext caller, long project, long document, long review, long suggestion,
                                   MessageRequest input, String key) {
        reviews.key(key);
        String text = input.text();
        if (text == null || text.isBlank()) throw invalid("비비에게 보낼 말을 입력해 주세요.");
        String operation = "review-message:" + suggestion, fingerprint = hash(List.of(review, suggestion, text));
        Prepared prepared = tx.execute(status -> {
            reviews.lock(caller, project, false);
            var copy = reviews.owned(caller, project, document);
            var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
            if (previous.isPresent()) return new Prepared(previous.get(), null, 0, null, null);
            requireOpen(project, copy);
            var reviewRow = reviews.currentReview(project, document, review, id(copy, "revision"));
            reviews.requireLatestReview(document, review);
            var row = suggestionRow(review, suggestion);
            if (repository.humanMessageCount(review) >= MAX_DISCUSSIONS) {
                throw new ApiException(HttpStatus.CONFLICT, "review_chat_limit", "이 검토에서는 비비와 " + MAX_DISCUSSIONS + "번까지 대화할 수 있어요. 다시 검토를 받아 주세요.");
            }
            var refs = reviews.references(project, document);
            var payload = reviews.reviewInput(copy, refs, "document-chat-" + document + "-" + UUID.randomUUID());
            payload.put("mode", "DISCUSS");
            payload.put("suggestion", suggestionInput(row));
            payload.put("messages", conversation(suggestion, text));
            return new Prepared(null, payload, id(copy, "revision"), payload.get("reference_hash").toString(), com.beez.backend.designitem.support.DesignJson.object(reviewRow.get("result")));
        });
        if (prepared.replay() != null) return JSON.readValue(prepared.replay(), MessageResponse.class);

        return callAndSave(project, prepared, envelope -> DocumentReviewResultValidator.validateReply(envelope, prepared.payload()), validated -> tx.execute(status -> {
            reviews.lock(caller, project, false);
            var copy = reviews.owned(caller, project, document);
            var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
            if (previous.isPresent()) return JSON.readValue(previous.get(), MessageResponse.class);
            requireOpen(project, copy);
            requireUnchanged(project, document, review, copy, prepared);
            suggestionRow(review, suggestion);
            var reply = DocumentReviewResultValidator.validateReply(validated.envelope(), prepared.payload());
            var human = repository.insertMessage(review, suggestion, "HUMAN", caller.accountId(), text, null);
            var bee = repository.insertMessage(review, suggestion, "BEE", null, reply.get("text").toString(),
                    (String) reply.get("proposed_text"));
            repository.recordUsage(project, validated.usage());
            var response = new MessageResponse(DocumentReviewService.messageView(human), DocumentReviewService.messageView(bee));
            repository.remember(project, caller.accountId(), operation, key, fingerprint, response);
            return response;
        }));
    }

    // ---- DOC-A23 ----

    @Transactional(propagation = Propagation.NEVER)
    public DirectResponse request(CallerContext caller, long project, long document, long review, DirectRequest input, String key) {
        reviews.key(key);
        String text = input.text();
        if (text == null || text.isBlank()) throw invalid("요청 내용을 입력해 주세요.");
        String operation = "review-request:" + review, fingerprint = hash(List.of(review, input.expectedRevision(), text));
        Prepared prepared = tx.execute(status -> {
            reviews.lock(caller, project, false);
            var copy = reviews.owned(caller, project, document);
            var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
            if (previous.isPresent()) return new Prepared(previous.get(), null, 0, null, null);
            reviews.initialWorkflow(project);
            reviews.editable(copy, input.expectedRevision());
            var reviewRow = reviews.currentReview(project, document, review, input.expectedRevision());
            reviews.requireLatestReview(document, review);
            if (repository.directRequestCount(review) >= MAX_DIRECT_REQUESTS) {
                throw new ApiException(HttpStatus.CONFLICT, "review_request_limit", "이 검토에서는 직접 요청을 " + MAX_DIRECT_REQUESTS + "번까지 할 수 있어요. 다시 검토를 받아 주세요.");
            }
            var refs = reviews.references(project, document);
            var payload = reviews.reviewInput(copy, refs, "document-request-" + document + "-" + UUID.randomUUID());
            payload.put("mode", "REQUEST");
            payload.put("request_text", text);
            return new Prepared(null, payload, id(copy, "revision"), payload.get("reference_hash").toString(), com.beez.backend.designitem.support.DesignJson.object(reviewRow.get("result")));
        });
        if (prepared.replay() != null) return JSON.readValue(prepared.replay(), DirectResponse.class);

        return callAndSave(project, prepared, envelope -> DocumentReviewResultValidator.validateRequested(envelope, prepared.payload(),
                prepared.reviewResult(), pendingRanges(review, prepared.payload().get("content").toString())), validated -> tx.execute(status -> {
            reviews.lock(caller, project, false);
            var copy = reviews.owned(caller, project, document);
            var previous = repository.replay(project, caller.accountId(), operation, key, fingerprint);
            if (previous.isPresent()) return JSON.readValue(previous.get(), DirectResponse.class);
            reviews.initialWorkflow(project);
            reviews.editable(copy, input.expectedRevision());
            requireUnchanged(project, document, review, copy, prepared);
            // 다른 직접 요청이 그사이 들어와 같은 구간을 차지했을 수 있다 — 저장 직전에 겹침을 다시 센다.
            var suggestion = DocumentReviewResultValidator.validateRequested(validated.envelope(), prepared.payload(),
                    prepared.reviewResult(), pendingRanges(review, prepared.payload().get("content").toString()));
            var row = repository.insertRequestedSuggestion(review, suggestion);
            repository.recordUsage(project, validated.usage());
            var response = new DirectResponse(reviews.suggestionView(row, List.of()));
            repository.remember(project, caller.accountId(), operation, key, fingerprint, response);
            return response;
        }));
    }

    // ---- 공통 ----

    private record Validated(Map<String, Object> envelope, List<Map<String, Object>> usage) { }

    /**
     * 트랜잭션·잠금 밖에서 AI 를 부르고, 사용량을 추출하고, 응답 형식을 미리 검증한 뒤 저장 함수에 넘긴다.
     * 실패(형식 위반 포함)하면 사용량만 기록하고 기존 검토는 건드리지 않는다. 저장 함수는 잠금 안에서 형식을 한 번 더 검증한다.
     */
    @SuppressWarnings("unchecked")
    private <T> T callAndSave(long project, Prepared prepared, java.util.function.Consumer<Map<String, Object>> preCheck,
                              java.util.function.Function<Validated, T> save) {
        List<Map<String, Object>> usage = List.of();
        String requestKey = prepared.payload().get("request_key").toString();
        try {
            // Network and model work never hold a project lock or DB transaction.
            Map<String, Object> envelope = ai.review(prepared.payload());
            usage = DocumentReviewUsageValidator.extract(envelope == null ? null : envelope.get("usage"), requestKey);
            if (envelope != null) { envelope = new HashMap<>(envelope); envelope.put("usage", usage); }
            preCheck.accept(envelope);
            return save.apply(new Validated(envelope, usage));
        } catch (RuntimeException failure) {
            if (failure instanceof org.springframework.web.client.RestClientResponseException responseError) {
                try {
                    var body = responseError.getResponseBodyAsString();
                    if (body.length() <= 1048576) usage = DocumentReviewUsageValidator.extract(JSON.readValue(body, Map.class).get("usage"), requestKey);
                } catch (RuntimeException ignored) { /* An unstructured transport error has unknown usage. */ }
            }
            if (!usage.isEmpty() && failure instanceof ApiException e && "invalid_ai_output".equals(e.getCode())) {
                usage = new ArrayList<>(usage);
                var last = new HashMap<>(usage.getLast()); last.put("failed", true); usage.set(usage.size() - 1, last);
            }
            var actualUsage = usage;
            tx.executeWithoutResult(status -> repository.recordUsage(project, actualUsage));
            if (failure instanceof ApiException) throw failure;
            throw reviews.unavailable("비비가 답하지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    /** 팀장 검토 요청 중에는 잠기고, 기준 설계가 만들어진 뒤에는 변경 제안으로 가야 한다. */
    private void requireOpen(long project, Map<String, Object> copy) {
        reviews.initialWorkflow(project);
        if ("SUBMITTED".equals(copy.get("status"))) throw conflict("submission_locked");
    }

    /** AI 호출 동안 본문(리비전)이나 참고 승인본이 바뀌었으면 오래된 응답은 버린다. */
    private void requireUnchanged(long project, long document, long review, Map<String, Object> copy, Prepared prepared) {
        if (id(copy, "revision") != prepared.revision()) throw conflict("review_stale");
        reviews.currentReview(project, document, review, prepared.revision());
        reviews.requireLatestReview(document, review);
        if (!prepared.referenceHash().equals(reviews.referenceHash(reviews.references(project, document)))) throw conflict("review_stale");
    }

    private Map<String, Object> suggestionRow(long review, long suggestion) {
        return repository.suggestions(review).stream().filter(s -> id(s, "id") == suggestion).findFirst()
                .orElseThrow(() -> AccessErrors.notFound("document_suggestion_missing"));
    }

    private static Map<String, Object> suggestionInput(Map<String, Object> row) {
        var view = new LinkedHashMap<String, Object>();
        view.put("id", row.get("id").toString());
        view.put("type", row.get("type"));
        view.put("step", row.get("step"));
        view.put("explanation", row.get("explanation"));
        view.put("original_text", row.get("original_text"));
        view.put("replacement_text", row.get("replacement_text"));
        view.put("decision", row.get("decision"));
        return view;
    }

    /** 지금까지의 대화에 새 메시지를 더해 AI 에 보낸다. 너무 긴 대화는 최근 것만 보낸다. */
    private List<Map<String, Object>> conversation(long suggestion, String newText) {
        var sent = new ArrayList<Map<String, Object>>();
        for (var m : repository.messagesOf(suggestion)) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("author_kind", m.get("author_kind"));
            entry.put("text", m.get("body"));
            if (m.get("proposed_text") != null) entry.put("proposed_text", m.get("proposed_text"));
            sent.add(entry);
        }
        var now = new LinkedHashMap<String, Object>();
        now.put("author_kind", "HUMAN"); now.put("text", newText);
        sent.add(now);
        return sent.size() > MAX_MESSAGES_SENT ? new ArrayList<>(sent.subList(sent.size() - MAX_MESSAGES_SENT, sent.size())) : sent;
    }

    /** 아직 처리하지 않은 PATCH 제안이 지금 본문에서 차지하는 구간. 새 제안은 이 구간과 겹치면 안 된다. */
    private List<int[]> pendingRanges(long review, String content) {
        var ranges = new ArrayList<int[]>();
        for (var s : repository.suggestions(review)) {
            if (!"PATCH".equals(s.get("type")) || !"PENDING".equals(s.get("decision"))) continue;
            String original = s.get("original_text").toString();
            int start = content.indexOf(original);
            if (start >= 0 && start == content.lastIndexOf(original)) ranges.add(new int[] {start, start + original.length()});
        }
        return ranges;
    }
}
