package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.dto.*;
import com.beez.backend.document.dto.ReviewChatDtos.DirectRequest;
import com.beez.backend.document.dto.ReviewChatDtos.MessageRequest;
import com.beez.backend.document.port.DocumentReviewGateway;
import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.document.service.DocumentReviewChatService;
import com.beez.backend.document.service.DocumentReviewService;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.document.support.DocumentReviewFixtures;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * S15P31A108-231 DOC-A22 대화 · DOC-A23 직접 요청 · DOC-A24 제출 취소 · DOC-A17 대화 대체문 반영.
 * AI 는 {@link DocumentReviewGateway} 대역이다(AI-A10 mode=DISCUSS/REQUEST 는 AI 쪽 미구현) — 실제 AI 연동 결과가 아니다.
 */
@TestPropertySource(properties = {"beez.cors.allowed-origins=https://app.beez.test", "beez.document.embedding-profile=review-test"})
class DocumentReviewChatIntegrationTest extends IntegrationTestSupport {
    static final String ORIGIN = "https://app.beez.test";
    static final JsonMapper JSON = JsonMapper.builder().build();
    @Autowired DocumentApplicationService documents;
    @Autowired DocumentReviewService reviews;
    @Autowired DocumentReviewChatService chat;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean DocumentStorage storage;
    @MockitoBean DocumentReviewGateway gateway;
    AccessFixtures.ProjectWithLeader project;
    CallerContext author, leader, other;
    Map<String, byte[]> files;
    long document;
    final List<Map<String, Object>> sent = new CopyOnWriteArrayList<>();
    volatile java.util.function.Function<Map<String, Object>, Map<String, Object>> chatAnswer;

    @BeforeEach void setup() throws Exception {
        project = fixtures().projectWithLeader();
        leader = CallerContext.human(project.leaderAccountId());
        long account = fixtures().account();
        fixtures().member(project.projectId(), account, ProjectRole.MEMBER);
        author = CallerContext.human(account);
        long otherAccount = fixtures().account();
        fixtures().member(project.projectId(), otherAccount, ProjectRole.MEMBER);
        other = CallerContext.human(otherAccount);
        files = new ConcurrentHashMap<>();
        when(storage.store(any(), anyString())).thenAnswer(call -> {
            String key = UUID.randomUUID() + "." + call.getArgument(1);
            files.put(key, call.getArgument(0)); return key;
        });
        when(storage.read(anyString())).thenAnswer(call -> files.get(call.getArgument(0)));
        doAnswer(call -> { files.remove(call.getArgument(0)); return null; }).when(storage).delete(anyString());
        sent.clear();
        chatAnswer = input -> chatReply(input, "확인 시각도 요구사항과 맞아요.", "팀원이 로그인한다.");
        when(gateway.review(anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("AI 호출 중에는 트랜잭션이 없다").isFalse();
            Map<String, Object> input = call.getArgument(0);
            sent.add(input);
            if (input.get("mode") != null) return chatAnswer.apply(input);
            return DocumentReviewFixtures.result(input, List.of(
                    Map.of("explanation", "권한 조건을 보완하세요.", "original_text", "로그인한다.", "replacement_text", "팀원만 로그인한다.")));
        });
        document = upload();
    }

    long upload() { return upload("# 계획\n로그인한다.\n표시한다.\n"); }

    @SuppressWarnings("unchecked")
    long upload(String text) { return upload(text, DocumentKind.PLAN); }

    @SuppressWarnings("unchecked")
    long upload(String text, DocumentKind kind) {
        var response = documents.upload(author, project.projectId(), kind, kind.name(), null,
                "plan.md", text.getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
        return Long.parseLong(((Map<String, Object>) response.get("document")).get("id").toString());
    }

    // ---- AI 응답 도우미 ----

    Map<String, Object> envelope(Map<String, Object> input, Map<String, Object> extra) {
        var result = new HashMap<String, Object>();
        for (String field : List.of("document_id", "revision", "content_hash", "reference_hash", "request_key")) result.put(field, input.get(field));
        result.putAll(extra);
        return Map.of("origin", "MODEL", "result", result, "usage", List.of(Map.of(
                "request_key", input.get("request_key"), "feature", "BEE_DRAFT", "model", "fixture-model",
                "latency_ms", 1, "failed", false, "tokens_known", false)));
    }

    Map<String, Object> chatReply(Map<String, Object> input, String text, String proposed) {
        var reply = new HashMap<String, Object>();
        reply.put("text", text);
        if (proposed != null) reply.put("proposed_text", proposed);
        return envelope(input, Map.of("reply", reply, "suggestions", List.of()));
    }

    Map<String, Object> requested(Map<String, Object> input, String original, String replacement) {
        return envelope(input, Map.of("suggestions", List.of(Map.of("type", "PATCH", "step", "BODY", "explanation", "요청대로 바꿉니다.",
                "original_text", original, "replacement_text", replacement, "sources", List.of()))));
    }

    // ---- 도우미 ----

    long pid() { return project.projectId(); }
    DocumentWorkingCopyResponse copy() { return reviews.get(author, pid(), document); }
    DocumentBeeReviewResponse review() { return reviewNow(author, pid(), document, copy().revision(), UUID.randomUUID().toString()); }
    long id(String value) { return Long.parseLong(value); }
    String key() { return UUID.randomUUID().toString(); }
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
    int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    /** 이 시험이 만든 문서의 대화 수(DB 는 시험끼리 공유한다). */
    int messageCount() { return count("SELECT count(*) FROM document_bee_message m JOIN document_bee_review r ON r.id=m.review_id JOIN document d ON d.id=r.document_id WHERE d.project_id=?", pid()); }
    DocumentMessageResponseHolder chatOnce(DocumentBeeReviewResponse review, String text, String key) {
        var response = chat.discuss(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new MessageRequest(text), key);
        return new DocumentMessageResponseHolder(response.message(), response.reply());
    }
    record DocumentMessageResponseHolder(DocumentMessageResponse message, DocumentMessageResponse reply) { }

    // ---- DOC-A22 ----

    @Test void conversationIsStoredPerSuggestionAndNeverChangesTheBody() {
        var review = review();
        String before = copy().content();
        var chatted = chatOnce(review, "읽음 대신 확인 시각을 남기면 어때?", key());
        assertThat(chatted.message()).extracting(DocumentMessageResponse::authorKind, DocumentMessageResponse::text)
                .containsExactly("HUMAN", "읽음 대신 확인 시각을 남기면 어때?");
        assertThat(chatted.reply()).extracting(DocumentMessageResponse::authorKind, DocumentMessageResponse::proposedText)
                .containsExactly("BEE", "팀원이 로그인한다.");
        // 본문·리비전·검토 결과는 그대로, 대화는 검토 결과(DOC-A13)에 함께 나온다
        assertThat(copy().content()).isEqualTo(before);
        assertThat(copy().revision()).isEqualTo(1);
        var suggestion = copy().latestReview().suggestions().getFirst();
        assertThat(suggestion.messages()).extracting(DocumentMessageResponse::authorKind).containsExactly("HUMAN", "BEE");
        assertThat(suggestion.decision()).isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=?", pid())).isEqualTo(2);   // 검토 1 + 대화 1
    }

    @Test void aiReceivesTheSuggestionAndTheWholeConversationSoFar() {
        var review = review();
        chatOnce(review, "첫 말", key());
        chatOnce(review, "두 번째 말", key());
        var last = sent.getLast();
        assertThat(last).containsEntry("mode", "DISCUSS");
        assertThat(((Number) last.get("revision")).longValue()).isEqualTo(1L);
        @SuppressWarnings("unchecked") var suggestion = (Map<String, Object>) last.get("suggestion");
        assertThat(suggestion).containsEntry("original_text", "로그인한다.").containsEntry("replacement_text", "팀원만 로그인한다.");
        @SuppressWarnings("unchecked") var messages = (List<Map<String, Object>>) last.get("messages");
        assertThat(messages).extracting(m -> m.get("author_kind") + ":" + m.get("text"))
                .containsExactly("HUMAN:첫 말", "BEE:확인 시각도 요구사항과 맞아요.", "HUMAN:두 번째 말");
    }

    @Test void sameKeyReplaysWithoutCallingAiAgainAndDifferentTextWithSameKeyConflicts() {
        var review = review();
        String key = "chat-once";
        var first = chatOnce(review, "같은 말", key);
        var again = chatOnce(review, "같은 말", key);
        assertThat(again).isEqualTo(first);
        verify(gateway, times(2)).review(anyMap());   // 검토 1 + 대화 1
        assertThat(count("SELECT count(*) FROM document_bee_message WHERE review_id=?", id(review.id()))).isEqualTo(2);
        code(() -> chatOnce(review, "다른 말", key), "idempotency_conflict");
    }

    @Test void onlyTheAuthorOfTheCopyAndOnlyItsOwnSuggestionsCanChat() {
        var review = review();
        long suggestion = id(review.suggestions().getFirst().id());
        code(() -> chat.discuss(other, pid(), document, id(review.id()), suggestion, new MessageRequest("말"), key()), "forbidden");
        code(() -> chat.discuss(leader, pid(), document, id(review.id()), suggestion, new MessageRequest("말"), key()), "forbidden");
        code(() -> chat.discuss(author, pid(), document, id(review.id()), suggestion + 999, new MessageRequest("말"), key()), "not_found");
        // 다른 문서의 검토·제안
        long second = upload("# 다른 계획\n로그인한다.\n", DocumentKind.API_SPEC);
        var secondReview = reviewNow(author, pid(), second, 1, key());
        code(() -> chat.discuss(author, pid(), document, id(secondReview.id()), id(secondReview.suggestions().getFirst().id()), new MessageRequest("말"), key()), "not_found");
        code(() -> chat.discuss(author, pid(), document, id(review.id()), id(secondReview.suggestions().getFirst().id()), new MessageRequest("말"), key()), "not_found");
        assertThat(messageCount()).isZero();
        verify(gateway, times(2)).review(anyMap());   // 검토 두 번뿐, 대화 호출은 없다
    }

    @Test void commentSuggestionsCanBeDiscussedToo() {
        doAnswer(call -> {
            Map<String, Object> input = call.getArgument(0);
            sent.add(input);
            if (input.get("mode") != null) return chatAnswer.apply(input);
            return DocumentReviewFixtures.result(input, List.of(Map.of("type", "COMMENT", "explanation", "표현을 다듬으면 좋아요.")));
        }).when(gateway).review(anyMap());
        var review = review();
        assertThat(review.suggestions().getFirst().type()).isEqualTo("COMMENT");
        chatAnswer = input -> chatReply(input, "표현만 다듬으면 돼요.", null);
        assertThat(chatOnce(review, "왜요?", key()).reply().text()).isNotBlank();
        // COMMENT 는 반영 대상이 아니라 비비가 대체문을 내면 형식 위반이다
        chatAnswer = input -> chatReply(input, "이렇게 바꿔요.", "다듬은 문장");
        code(() -> chatOnce(review, "고쳐 줘", key()), "invalid_ai_output");
        assertThat(messageCount()).isEqualTo(2);
    }

    @Test void discussionCountPerReviewIsCapped() {
        var review = review();
        long suggestion = id(review.suggestions().getFirst().id());
        for (int i = 0; i < 80; i++) {
            jdbc.update("INSERT INTO document_bee_message(review_id, suggestion_id, author_kind, author_account_id, body) VALUES (?,?,'HUMAN',?,'말')",
                    id(review.id()), suggestion, author.accountId());
        }
        int calls = sent.size();
        code(() -> chatOnce(review, "한 번 더", key()), "review_chat_limit");
        assertThat(sent).hasSize(calls);
    }

    @Test void directEditAfterTheReviewMakesItStaleAndAiIsNotCalled() {
        var review = review();
        reviews.edit(author, pid(), document, new WorkingCopyUpdateRequest(1, "# 수정\n로그인한다.\n", "plan.md"));
        int calls = sent.size();
        code(() -> chatOnce(review, "말", key()), "review_stale");
        assertThat(sent).hasSize(calls);
    }

    @Test void bodyChangedWhileAiRunsRejectsTheOldAnswerAndStoresNothing() {
        var review = review();
        chatAnswer = input -> {
            // AI 가 답하는 동안 등록자가 본문을 직접 고친다
            reviews.edit(author, pid(), document, new WorkingCopyUpdateRequest(1, "# 그사이 수정\n로그인한다.\n", "plan.md"));
            return chatReply(input, "늦은 답", null);
        };
        code(() -> chatOnce(review, "말", key()), "review_stale");
        assertThat(messageCount()).isZero();
        assertThat(copy().content()).contains("그사이 수정");
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=?", pid())).as("호출은 일어났으므로 사용량은 남는다").isEqualTo(2);
    }

    @Test void oldReviewIsStaleOnceANewerReviewExistsEvenWithTheSameBody() {
        var old = review();
        var latest = review();   // 본문이 같아도 재검토는 새 검토다
        assertThat(latest.id()).isNotEqualTo(old.id());
        int calls = sent.size();
        code(() -> chatOnce(old, "말", key()), "review_stale");
        code(() -> chat.request(author, pid(), document, id(old.id()), new DirectRequest(1, "고쳐 줘"), key()), "review_stale");
        assertThat(sent).hasSize(calls);
        assertThat(messageCount()).isZero();
        assertThat(chatOnce(latest, "말", key()).reply().text()).isNotBlank();
    }

    @Test void newerReviewFinishingWhileAiRunsRejectsTheAnswerForTheOldReview() {
        var old = review();
        chatAnswer = input -> {
            review();   // AI 가 답하는 동안 같은 본문으로 재검토가 끝난다
            return chatReply(input, "늦은 답", null);
        };
        code(() -> chatOnce(old, "말", key()), "review_stale");
        assertThat(messageCount()).isZero();
    }

    @Test void conversationIsLockedWhileTheLeaderReviewsTheSubmission() {
        var review = review();
        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "SKIP"));
        reviews.submit(author, pid(), document, new DocumentSubmissionRequest(1, review.id()), key());
        code(() -> chatOnce(review, "말", key()), "submission_locked");
        assertThat(messageCount()).isZero();
    }

    @Test void aiFailureLeavesTheReviewAndConversationUntouched() {
        var review = review();
        chatAnswer = input -> { throw new org.springframework.web.client.ResourceAccessException("down"); };
        code(() -> chatOnce(review, "말", key()), "review_unavailable");
        chatAnswer = input -> envelope(input, Map.of("suggestions", List.of()));   // reply 없음
        code(() -> chatOnce(review, "말", key()), "invalid_ai_output");
        chatAnswer = input -> envelope(input, Map.of("reply", Map.of("text", "답"), "suggestions", List.of(Map.of("type", "PATCH"))));   // DISCUSS 는 제안을 만들 수 없다
        code(() -> chatOnce(review, "말", key()), "invalid_ai_output");
        assertThat(messageCount()).isZero();
        assertThat(copy().latestReview().suggestions()).hasSize(1);
        assertThat(copy().latestReview().id()).isEqualTo(review.id());
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND failed", pid())).as("형식 위반 호출은 실패 사용량으로 남는다").isEqualTo(2);
    }

    // ---- DOC-A17 APPLY 대체문 ----

    @Test void applyingTheBeeProposalUsesOnlyAnExactTextFromTheConversation() {
        var review = review();
        long suggestion = id(review.suggestions().getFirst().id());
        code(() -> reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(1, "APPLY", "아무 말")), "validation_failed");
        code(() -> reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(1, "SKIP", "팀원이 로그인한다.")), "validation_failed");
        chatOnce(review, "다르게 하면?", key());
        chatAnswer = input -> chatReply(input, "이런 방법도 있어요.", "누구나 로그인한다.");
        chatOnce(review, "다른 방법은?", key());
        code(() -> reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(1, "APPLY", "팀원이 로그인한다")), "validation_failed");
        assertThat(copy().content()).doesNotContain("팀원이");

        var applied = reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(1, "APPLY", "팀원이 로그인한다."));
        assertThat(applied.content()).contains("팀원이 로그인한다.").doesNotContain("팀원만 로그인한다.");
        assertThat(applied.revision()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT decision,applied_replacement_text FROM document_bee_suggestion WHERE id=?", suggestion))
                .containsEntry("decision", "APPLIED").containsEntry("applied_replacement_text", "팀원이 로그인한다.");
        // 같은 요청을 다시 보내면 그대로, 다른 문장이면 충돌
        assertThat(reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(applied.revision(), "APPLY", "팀원이 로그인한다.")).revision())
                .isEqualTo(2);
        code(() -> reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(applied.revision(), "APPLY", "누구나 로그인한다.")), "version_conflict");
        // 대화에 없는 문장은 (제안에 원래 저장된 대체문이라도) 거절한다
        code(() -> reviews.decideSuggestion(author, pid(), document, id(review.id()), suggestion, new SuggestionDecisionRequest(applied.revision(), "APPLY", "팀원만 로그인한다.")), "validation_failed");
    }

    @Test void applyingWithoutReplacementKeepsTheStoredSuggestionText() {
        var review = review();
        chatOnce(review, "말", key());
        var applied = reviews.decideSuggestion(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "APPLY"));
        assertThat(applied.content()).contains("팀원만 로그인한다.");
        assertThat(jdbc.queryForObject("SELECT applied_replacement_text FROM document_bee_suggestion WHERE id=?", String.class, id(review.suggestions().getFirst().id()))).isNull();
    }

    // ---- DOC-A23 ----

    @Test void directRequestAddsAPendingPatchToTheSameReviewAndBlocksSubmissionUntilHandled() {
        var review = review();
        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "SKIP"));
        chatAnswer = input -> requested(input, "표시한다.", "표시하고 기록한다.");
        var created = chat.request(author, pid(), document, id(review.id()), new DirectRequest(1, "표시한다 뒤에 기록도 해줘"), key()).suggestion();
        assertThat(created).extracting(DocumentSuggestionResponse::origin, DocumentSuggestionResponse::decision, DocumentSuggestionResponse::type, DocumentSuggestionResponse::step)
                .containsExactly("DIRECT_REQUEST", "PENDING", "PATCH", "BODY");
        assertThat(created.originalText()).isEqualTo("표시한다.");
        var stored = copy().latestReview();
        assertThat(stored.id()).isEqualTo(review.id());
        assertThat(stored.suggestions()).hasSize(2);
        assertThat(stored.referenceHash()).isEqualTo(review.referenceHash());
        assertThat(stored.summary()).isEqualTo(review.summary());   // 검토 결과는 요청으로 바뀌지 않는다
        assertThat(sent.getLast()).containsEntry("mode", "REQUEST").containsEntry("request_text", "표시한다 뒤에 기록도 해줘");
        code(() -> reviews.submit(author, pid(), document, new DocumentSubmissionRequest(1, review.id()), key()), "review_decisions_pending");
        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(created.id()), new SuggestionDecisionRequest(1, "APPLY"));
        reviews.submit(author, pid(), document, new DocumentSubmissionRequest(2, review.id()), key());
    }

    @Test void directRequestRulesStaleRevisionOverlapAndInvalidOutput() {
        var review = review();
        long reviewId = id(review.id());
        code(() -> chat.request(author, pid(), document, reviewId, new DirectRequest(5, "말"), key()), "version_conflict");
        // 아직 처리하지 않은 제안("로그인한다.")과 겹치는 구간, 본문에 없는 원문, 두 곳 이상 나오는 원문, 제안 2개
        for (var bad : List.<Map<String, Object>>of(Map.of("o", "로그인한다.\n표시", "r", "x"), Map.of("o", "없는 문장", "r", "x"), Map.of("o", "한다.", "r", "x"))) {
            chatAnswer = input -> requested(input, bad.get("o").toString(), bad.get("r").toString());
            code(() -> chat.request(author, pid(), document, reviewId, new DirectRequest(1, "말"), key()), "invalid_ai_output");
        }
        chatAnswer = input -> envelope(input, Map.of("suggestions", List.of()));
        code(() -> chat.request(author, pid(), document, reviewId, new DirectRequest(1, "말"), key()), "invalid_ai_output");
        assertThat(copy().latestReview().suggestions()).hasSize(1);
        // 삭제 요청: 빈 대체문은 그 구간을 지운다
        chatAnswer = input -> requested(input, "표시한다.", "");
        assertThat(chat.request(author, pid(), document, reviewId, new DirectRequest(1, "지워줘"), key()).suggestion().replacementText()).isEmpty();
    }

    @Test void directRequestIsIdempotentAuthorOnlyAndLockedAfterSubmission() {
        var review = review();
        long reviewId = id(review.id());
        chatAnswer = input -> requested(input, "표시한다.", "표시하고 기록한다.");
        var first = chat.request(author, pid(), document, reviewId, new DirectRequest(1, "기록도"), "req-1");
        assertThat(chat.request(author, pid(), document, reviewId, new DirectRequest(1, "기록도"), "req-1")).isEqualTo(first);
        assertThat(copy().latestReview().suggestions()).hasSize(2);
        code(() -> chat.request(other, pid(), document, reviewId, new DirectRequest(1, "x"), key()), "forbidden");
        reviews.decideSuggestion(author, pid(), document, reviewId, id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "SKIP"));
        reviews.decideSuggestion(author, pid(), document, reviewId, id(first.suggestion().id()), new SuggestionDecisionRequest(1, "SKIP"));
        reviews.submit(author, pid(), document, new DocumentSubmissionRequest(1, review.id()), key());
        code(() -> chat.request(author, pid(), document, reviewId, new DirectRequest(1, "또"), key()), "submission_locked");
    }

    // ---- DOC-A24 ----

    DocumentSubmissionResponse submitted() {
        var review = review();
        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "SKIP"));
        chatOnce(review, "제출 전에 상의", key());
        return reviews.submit(author, pid(), document, new DocumentSubmissionRequest(1, review.id()), key());
    }

    @Test void withdrawingReopensTheCopyAndKeepsEveryPriorRecord() {
        var submission = submitted();
        long version = id(submission.documentVersionId());
        var reopened = reviews.withdraw(author, pid(), document, id(submission.id()), key());
        assertThat(reopened.status()).isEqualTo("EDITING");
        assertThat(reopened.revision()).isEqualTo(2);
        assertThat(reopened.submissions()).extracting(DocumentSubmissionResponse::status).containsExactly("WITHDRAWN");
        // 이력 보존: 제출·그때의 본문·검토·제안·대화가 그대로 조회된다
        var detail = reviews.submission(author, pid(), document, id(submission.id()));
        assertThat(detail.submission().status()).isEqualTo("WITHDRAWN");
        assertThat(detail.content()).contains("로그인한다.");
        assertThat(detail.review().suggestions().getFirst().messages()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?", String.class, version)).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM document_review_submission WHERE document_id=?", document)).isEqualTo(1);
        // 이전 검토는 오래된 것이 되고(반려 뒤 재작성과 같다) 새 대화·요청은 이전 제출 자료를 바꾸지 않는다
        code(() -> reviews.submit(author, pid(), document, new DocumentSubmissionRequest(2, detail.review().id()), key()), "review_stale");
        code(() -> chat.discuss(author, pid(), document, id(detail.review().id()), id(detail.review().suggestions().getFirst().id()), new MessageRequest("말"), key()), "review_stale");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO document_bee_message(review_id,suggestion_id,author_kind,body) VALUES(?,?,'BEE','x')",
                id(detail.review().id()), id(detail.review().suggestions().getFirst().id()))).hasMessageContaining("변경할 수 없습니다");
    }

    @Test void afterWithdrawingTheAuthorEditsReReviewsAndSubmitsAgainWithHistoryIntact() {
        var first = submitted();
        reviews.withdraw(author, pid(), document, id(first.id()), key());
        reviews.edit(author, pid(), document, new WorkingCopyUpdateRequest(2, "# 계획\n로그인한다.\n표시한다.\n수정했다.\n", "plan.md"));
        var next = reviewNow(author, pid(), document, 3, key());
        reviews.decideSuggestion(author, pid(), document, id(next.id()), id(next.suggestions().getFirst().id()), new SuggestionDecisionRequest(3, "SKIP"));
        var second = reviews.submit(author, pid(), document, new DocumentSubmissionRequest(3, next.id()), key());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.documentVersionId()).isNotEqualTo(first.documentVersionId());
        assertThat(copy().submissions()).extracting(DocumentSubmissionResponse::status).containsExactlyInAnyOrder("WITHDRAWN", "SUBMITTED");
        assertThat(reviews.submission(author, pid(), document, id(first.id())).content()).doesNotContain("수정했다.");
        reviews.decide(leader, pid(), document, id(second.id()), new DocumentDecisionRequest("APPROVE", null));
        assertThat(copy().status()).isEqualTo("APPROVED");
    }

    @Test void withdrawIsAuthorOnlyIdempotentAndRefusedOnceDecided() {
        var submission = submitted();
        long id = id(submission.id());
        code(() -> reviews.withdraw(other, pid(), document, id, key()), "forbidden");
        code(() -> reviews.withdraw(leader, pid(), document, id, key()), "forbidden");
        code(() -> reviews.withdraw(author, pid(), document, id + 999, key()), "not_found");
        String key = "withdraw-once";
        var first = reviews.withdraw(author, pid(), document, id, key);
        assertThat(reviews.withdraw(author, pid(), document, id, key)).isEqualTo(first);
        assertThat(reviews.withdraw(author, pid(), document, id, "another-key").status()).as("이미 취소한 제출을 다시 취소해도 같은 결과").isEqualTo("EDITING");
        assertThat(copy().revision()).as("두 번 올리지 않는다").isEqualTo(2);
        code(() -> reviews.decide(leader, pid(), document, id, new DocumentDecisionRequest("APPROVE", null)), "submission_withdrawn");
        code(() -> reviews.decide(leader, pid(), document, id, new DocumentDecisionRequest("REJECT", "사유")), "submission_withdrawn");

        // 결정 뒤에는 취소할 수 없다
        var review = review();
        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(2, "SKIP"));
        var next = reviews.submit(author, pid(), document, new DocumentSubmissionRequest(2, review.id()), key());
        reviews.decide(leader, pid(), document, id(next.id()), new DocumentDecisionRequest("REJECT", "보완 필요"));
        code(() -> reviews.withdraw(author, pid(), document, id(next.id()), key()), "submission_decided");
    }

    @Test void approvingAndWithdrawingAtTheSameTimeLetsExactlyOneWin() throws Exception {
        var submission = submitted();
        long id = id(submission.id());
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<String> approve = () -> { go.await(); try { reviews.decide(leader, pid(), document, id, new DocumentDecisionRequest("APPROVE", null)); return "APPROVED"; }
                catch (ApiException e) { return e.getCode(); } };
            Callable<String> withdraw = () -> { go.await(); try { reviews.withdraw(author, pid(), document, id, key()); return "WITHDRAWN"; }
                catch (ApiException e) { return e.getCode(); } };
            var a = pool.submit(approve);
            var w = pool.submit(withdraw);
            go.countDown();
            var results = List.of(a.get(30, TimeUnit.SECONDS), w.get(30, TimeUnit.SECONDS));
            String status = jdbc.queryForObject("SELECT status FROM document_review_submission WHERE id=?", String.class, id);
            if (status.equals("APPROVED")) assertThat(results).containsExactly("APPROVED", "submission_decided");
            else assertThat(results).containsExactly("submission_withdrawn", "WITHDRAWN");
            assertThat(count("SELECT count(*) FROM document_review_submission WHERE document_id=? AND status IN ('APPROVED','WITHDRAWN')", document)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test void withdrawnSubmissionsAreListedByStatusButNotAsPendingWork() {
        var submission = submitted();
        assertThat(reviews.submissions(leader, pid(), "SUBMITTED")).extracting(DocumentSubmissionResponse::id).contains(submission.id());
        reviews.withdraw(author, pid(), document, id(submission.id()), key());
        assertThat(reviews.submissions(leader, pid(), "SUBMITTED")).extracting(DocumentSubmissionResponse::id).doesNotContain(submission.id());
        assertThat(reviews.submissions(leader, pid(), "WITHDRAWN")).extracting(DocumentSubmissionResponse::id).contains(submission.id());
    }

    // ---- HTTP ----

    @Test void httpContract() throws Exception {
        var review = review();
        ApiClient api = client().login(fixtures().emailOf(author.accountId()), AccessFixtures.PASSWORD);
        String base = "/api/projects/" + pid() + "/documents/" + document;
        String messages = base + "/bee-reviews/" + review.id() + "/suggestions/" + review.suggestions().getFirst().id() + "/messages";
        assertThat(api.post(messages, "{\"text\":\"말\"}", "Idempotency-Key", "h1").statusCode()).as("Origin 필요").isEqualTo(403);
        var created = api.post(messages, "{\"text\":\"말\"}", "Origin", ORIGIN, "Idempotency-Key", "h1");
        assertThat(created.statusCode()).isEqualTo(201);
        var body = JSON.readTree(created.body());
        assertThat(body.get("message").get("author_kind").asString()).isEqualTo("HUMAN");
        assertThat(body.get("reply").get("proposed_text").asString()).isEqualTo("팀원이 로그인한다.");
        assertThat(api.post(messages, "{\"text\":\" \"}", "Origin", ORIGIN, "Idempotency-Key", "h2").statusCode()).isEqualTo(400);

        chatAnswer = input -> requested(input, "표시한다.", "표시하고 기록한다.");
        var direct = api.post(base + "/bee-reviews/" + review.id() + "/requests", "{\"expected_revision\":1,\"text\":\"기록도\"}", "Origin", ORIGIN, "Idempotency-Key", "h3");
        assertThat(direct.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(direct.body()).get("suggestion").get("origin").asString()).isEqualTo("DIRECT_REQUEST");

        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(1, "SKIP"));
        reviews.decideSuggestion(author, pid(), document, id(review.id()), id(JSON.readTree(direct.body()).get("suggestion").get("id").asString()), new SuggestionDecisionRequest(1, "SKIP"));
        var submission = reviews.submit(author, pid(), document, new DocumentSubmissionRequest(1, review.id()), key());
        var withdraw = api.post(base + "/review-submissions/" + submission.id() + "/withdraw", "{}", "Origin", ORIGIN, "Idempotency-Key", "h4");
        assertThat(withdraw.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(withdraw.body()).get("status").asString()).isEqualTo("EDITING");
    }

    /** 접수 + 워커 처리(S15P31A108-391). 동기 호출이던 때와 같은 결과·오류 코드를 돌려준다. */
    com.beez.backend.document.dto.DocumentBeeReviewResponse reviewNow(com.beez.backend.common.auth.CallerContext caller,
            long projectId, long documentId, long revision, String key) {
        return com.beez.backend.document.support.DocumentReviewFixtures.reviewNow(reviews, jdbc, caller, projectId, documentId, revision, key);
    }
}
