package com.beez.backend.document;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.dto.*;
import com.beez.backend.document.port.DocumentReviewGateway;
import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.document.service.DocumentReviewService;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestPropertySource(properties = {"beez.cors.allowed-origins=https://app.beez.test", "beez.document.embedding-profile=review-test"})
class DocumentReviewIntegrationTest extends IntegrationTestSupport {
    @Autowired DocumentApplicationService documents;
    @Autowired DocumentReviewService reviews;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.beez.backend.document.service.DocumentOriginalCleanupWorker cleanup;
    @Autowired com.beez.backend.designitem.service.DesignItemCommandService items;
    @Autowired com.beez.backend.designitem.service.DesignItemQueryService itemQueries;
    @Autowired com.beez.backend.designitem.service.BaselinePublishService baselines;
    @MockitoBean DocumentStorage storage;
    @MockitoBean DocumentReviewGateway gateway;
    AccessFixtures.ProjectWithLeader project;
    CallerContext author, leader;
    Map<String, byte[]> files;
    long document;

    @BeforeEach void setup() throws Exception {
        project = fixtures().projectWithLeader();
        leader = CallerContext.human(project.leaderAccountId());
        long account = fixtures().account();
        fixtures().member(project.projectId(), account, ProjectRole.MEMBER);
        author = CallerContext.human(account);
        files = new ConcurrentHashMap<>();
        when(storage.store(any(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String key = UUID.randomUUID() + "." + call.getArgument(1);
            files.put(key, call.getArgument(0)); return key;
        });
        when(storage.read(anyString())).thenAnswer(call -> files.get(call.getArgument(0)));
        doAnswer(call -> { files.remove(call.getArgument(0)); return null; }).when(storage).delete(anyString());
        when(gateway.review(anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return result(call.getArgument(0), List.of());
        });
        document = upload("PLAN");
    }

    @SuppressWarnings("unchecked")
    long upload(String kind) {
        var response = documents.upload(author, project.projectId(), DocumentKind.valueOf(kind), kind, null,
                "plan.md", "# 계획\n로그인한다.\n".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
        return Long.parseLong(((Map<String, Object>) response.get("document")).get("id").toString());
    }

    Map<String, Object> result(Map<String, Object> input, List<?> suggestions) {
        return com.beez.backend.document.support.DocumentReviewFixtures.result(input, suggestions);
    }

    DocumentWorkingCopyResponse copy() { return reviews.get(author, project.projectId(), document); }
    DocumentBeeReviewResponse review() {
        return reviews.review(author, project.projectId(), document, copy().revision(), UUID.randomUUID().toString());
    }
    DocumentSubmissionResponse submit(DocumentBeeReviewResponse review) {
        return reviews.submit(author, project.projectId(), document,
                new DocumentSubmissionRequest(copy().revision(), review.id()), UUID.randomUUID().toString());
    }
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test void rejectionSelectsRerunAndPersistsClassificationsWithoutBreakingIdempotentReplay() {
        var calls = new ArrayList<Map<String, Object>>();
        doAnswer(call -> {
            Map<String, Object> input = call.getArgument(0); calls.add(new HashMap<>(input));
            if (!"RERUN".equals(input.get("mode"))) return result(input, List.of());
            return result(input, List.of(Map.of("type", "COMMENT", "step", "BODY", "explanation", "사유 확인",
                    "category", "REJECTION_FIX", "severity", "MAJOR")));
        }).when(gateway).review(anyMap());
        var initial = review();
        assertThat(calls.getFirst()).doesNotContainKey("mode");
        var submission = submit(initial);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()),
                new DocumentDecisionRequest("REJECT", "누락 항목을 추가해 주세요."));
        long revision = copy().revision();
        var rerun = reviews.review(author, project.projectId(), document, revision, "rerun-once");
        assertThat(calls).hasSize(2);
        assertThat(calls.getLast()).containsEntry("mode", "RERUN")
                .containsEntry("rejection_submission_id", submission.id())
                .containsEntry("rejection_reason", "누락 항목을 추가해 주세요.");
        assertThat(rerun.suggestions()).singleElement().satisfies(suggestion -> {
            assertThat(suggestion.category()).isEqualTo("REJECTION_FIX");
            assertThat(suggestion.severity()).isEqualTo("MAJOR");
        });
        assertThat(jdbc.queryForMap("SELECT category,severity FROM document_bee_suggestion WHERE review_id=?", Long.parseLong(rerun.id())))
                .containsEntry("category", "REJECTION_FIX").containsEntry("severity", "MAJOR");
        assertThat(reviews.review(author, project.projectId(), document, revision, "rerun-once")).isEqualTo(rerun);
        assertThat(calls).hasSize(2);
    }

    @Test void authorReviewsAppliesSubmitsAndLeaderRejectsWithDurableReasonThenSameCaseResubmits() {
        doAnswer(call -> result(call.getArgument(0), List.of(
                Map.of("explanation", "권한 조건을 보완하세요.", "original_text", "로그인한다.", "replacement_text", "팀원만 로그인한다."))))
                .when(gateway).review(anyMap());
        var review = review();
        assertThat(review.unavailableReferences()).containsExactly("API_SPEC", "ERD", "REQ_SPEC");
        code(() -> submit(review), "review_decisions_pending");
        var updated = reviews.decideSuggestion(author, project.projectId(), document, Long.parseLong(review.id()),
                Long.parseLong(review.suggestions().getFirst().id()), new SuggestionDecisionRequest(copy().revision(), "APPLY"));
        assertThat(updated.content()).contains("팀원만 로그인한다.");
        String key = "submit-once";
        var request = new DocumentSubmissionRequest(updated.revision(), review.id());
        var submission = reviews.submit(author, project.projectId(), document, request, key);
        assertThat(reviews.submit(author, project.projectId(), document, request, key)).isEqualTo(submission);
        assertThat(jdbc.queryForObject("SELECT version_no FROM document_version WHERE id=?", Integer.class, Long.parseLong(submission.documentVersionId()))).isEqualTo(1);
        code(() -> reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(updated.revision(), "수정", "plan.md")), "submission_locked");
        code(() -> reviews.decide(author, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("APPROVE", null)), "forbidden");
        code(() -> reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("REJECT", " ")), "validation_failed");
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("REJECT", "누락 항목을 추가해 주세요."));
        assertThat(reviews.submission(author, project.projectId(), document, Long.parseLong(submission.id()))
                .submission().rejectionReason()).contains("누락 항목");
        code(() -> reviews.submit(author, project.projectId(), document, new DocumentSubmissionRequest(copy().revision(), review.id()), "stale"), "review_stale");
        reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(copy().revision(), "# 보완\n로그인한다.", "plan.md"));
        var nextReview = review();
        reviews.decideSuggestion(author, project.projectId(), document, Long.parseLong(nextReview.id()),
                Long.parseLong(nextReview.suggestions().getFirst().id()), new SuggestionDecisionRequest(copy().revision(), "SKIP"));
        var resubmission = submit(nextReview);
        assertThat(resubmission.documentId()).isEqualTo(submission.documentId());
        assertThat(resubmission.documentVersionId()).isNotEqualTo(submission.documentVersionId());
        assertThat(copy().submissions()).hasSize(2);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(resubmission.id()), new DocumentDecisionRequest("APPROVE", null));
        assertThat(copy().status()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, project.projectId())).isZero();
        assertThat(documents.get(author, project.projectId(), document, null).toString()).contains("보완");
    }

    List<Map<String, Object>> notifications(String type) {
        return jdbc.queryForList("SELECT * FROM notification WHERE project_id=? AND type=? ORDER BY id", project.projectId(), type);
    }

    List<Map<String, Object>> activities(String type) {
        return jdbc.queryForList("SELECT * FROM activity_event WHERE project_id=? AND event_type=? ORDER BY id", project.projectId(), type);
    }

    /** S15P31A108-310: 등록·승인·반려가 알림과 별개로 타임라인에 한 번씩 남는다(재생·재요청은 늘지 않고 사유는 담기지 않는다). */
    @Test void registrationApprovalAndRejectionLeaveOneActivityEachWithoutTheReason() {
        long firstVersion = jdbc.queryForObject("SELECT id FROM document_version WHERE document_id=?", Long.class, document);
        assertThat(activities("DOCUMENT_REGISTERED")).singleElement().satisfies(row -> {
            assertThat(row.get("actor_kind")).isEqualTo("HUMAN");
            assertThat(row.get("actor_id")).isEqualTo(author.accountId());
            assertThat(row.get("ref_kind")).isEqualTo("DOCUMENT_VERSION");
            assertThat(row.get("ref_id")).isEqualTo(firstVersion);
            assertThat(row.get("dedupe_key")).isEqualTo("document-version:" + firstVersion + ":registered");
            assertThat(row.get("result")).isEqualTo("SUCCESS");
        });

        var review = review();
        var submission = submit(review);
        var reject = new DocumentDecisionRequest("REJECT", "민감한 반려 사유 원문");
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), reject);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), reject);
        assertThat(activities("DOCUMENT_REJECTED")).singleElement().satisfies(row -> {
            assertThat(row.get("actor_id")).isEqualTo(leader.accountId());
            assertThat(row.get("ref_kind")).isEqualTo("DOCUMENT_VERSION");
            assertThat(row.get("ref_id")).isEqualTo(Long.parseLong(submission.documentVersionId()));
            assertThat(row.get("dedupe_key")).isEqualTo("doc-submission:" + submission.id() + ":rejected");
            assertThat(row.toString()).doesNotContain("민감한 반려 사유 원문");
        });
        assertThat(activities("DOCUMENT_APPROVED")).isEmpty();
        assertThat(activities("DOCUMENT_REGISTERED")).hasSize(1);
    }

    @Test void approvalLeavesOneActivityEvenWhenRepeated() {
        var submission = submit(review());
        var approve = new DocumentDecisionRequest("APPROVE", null);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), approve);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), approve);
        assertThat(activities("DOCUMENT_APPROVED")).singleElement().satisfies(row -> {
            assertThat(row.get("actor_id")).isEqualTo(leader.accountId());
            assertThat(row.get("dedupe_key")).isEqualTo("doc-submission:" + submission.id() + ":approved");
        });
        assertThat(activities("DOCUMENT_REJECTED")).isEmpty();
    }

    @Test void beeReviewSubmissionAndDecisionNotifyTheRightPeopleOnceWithoutTheReason() {
        var review = review();
        assertThat(notifications("DOCUMENT_BEE_REVIEW_DONE")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(author.accountId());
            assertThat(row.get("ref_kind")).isEqualTo("DOCUMENT_VERSION");
            assertThat(row.get("document_id")).isEqualTo(document);
            // 파일명이 아니라 문서 제목으로 알리고 해요체로 쓴다 (S15P31A108-370)
            assertThat(row.get("title").toString()).startsWith("비비의 문서 검토가 끝났어요: ").doesNotContain(".md");
            assertThat(row.get("dedupe_key")).isEqualTo("document-review:" + review.id() + ":bee-review-done:to:" + author.accountId());
        });

        var request = new DocumentSubmissionRequest(copy().revision(), review.id());
        var submission = reviews.submit(author, project.projectId(), document, request, "notify-submit");
        assertThat(reviews.submit(author, project.projectId(), document, request, "notify-submit")).isEqualTo(submission);
        assertThat(notifications("DOCUMENT_REVIEW_REQUESTED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(leader.accountId());
            assertThat(row.get("ref_id")).isEqualTo(Long.parseLong(submission.documentVersionId()));
            assertThat(row.get("document_id")).isEqualTo(document);
            assertThat(row.get("dedupe_key")).isEqualTo("doc-submission:" + submission.id() + ":review-requested:to:" + leader.accountId());
        });

        var reject = new DocumentDecisionRequest("REJECT", "민감한 반려 사유 원문");
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), reject);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), reject);
        assertThat(notifications("DOCUMENT_REJECTED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(author.accountId());
            assertThat(row.get("title").toString() + row.get("body")).doesNotContain("민감한 반려 사유 원문");
        });
        assertThat(notifications("DOCUMENT_APPROVED")).isEmpty();
        // 요청은 한 번, 결과는 한 번 — 재생·재요청이 알림을 늘리지 않았다
        assertThat(notifications("DOCUMENT_REVIEW_REQUESTED")).hasSize(1);
    }

    @Test void approvalNotifiesTheRegistrant() {
        var review = review();
        var submission = submit(review);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("APPROVE", null));
        assertThat(notifications("DOCUMENT_APPROVED")).singleElement()
                .satisfies(row -> assertThat(row.get("recipient_account_id")).isEqualTo(author.accountId()));
    }

    @Test void evenLeaderCannotEditOtherAuthorsCopyAndAgentOrRemovedMemberCannotWrite() {
        code(() -> reviews.edit(leader, project.projectId(), document, new WorkingCopyUpdateRequest(1, "다른 내용", "plan.md")), "forbidden");
        long token = fixtures().agentToken(project.projectId(), author.accountId());
        code(() -> reviews.edit(CallerContext.agent(author.accountId(), token), project.projectId(), document,
                new WorkingCopyUpdateRequest(1, "다른 내용", "plan.md")), "forbidden");
        jdbc.update("UPDATE project_member SET active=false WHERE project_id=? AND account_id=?", project.projectId(), author.accountId());
        code(() -> reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "다른 내용", "plan.md")), "not_found");
    }

    @Test void directEditInvalidatesReviewAndSameKeyReplaysWithoutCallingAiAgain() {
        var first = reviews.review(author, project.projectId(), document, 1, "same");
        assertThat(reviews.review(author, project.projectId(), document, 1, "same")).isEqualTo(first);
        verify(gateway, times(1)).review(anyMap());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=?", Integer.class, project.projectId())).isEqualTo(1);
        reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "수정된 내용", "plan.md"));
        assertThat(reviews.review(author, project.projectId(), document, 1, "same")).isEqualTo(first);
        code(() -> submit(first), "review_stale");
        code(() -> reviews.review(author, project.projectId(), document, 2, "same"), "idempotency_conflict");
    }

    @Test void suggestionConstraintsPreserveLegacyPatchAndRejectSelectableComments() {
        var review = review();
        long id = Long.parseLong(review.id());
        jdbc.update("""
                INSERT INTO document_bee_suggestion(review_id,explanation,original_text,replacement_text)
                VALUES(?,'이전 PATCH','로그인한다.','')
                """, id);
        var legacy = copy().latestReview().suggestions().getFirst();
        assertThat(legacy.type()).isEqualTo("PATCH");
        assertThat(legacy.decision()).isEqualTo("PENDING");
        assertThat(legacy.step()).isNull();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO document_bee_suggestion(review_id,type,explanation,decision)
                VALUES(?,'COMMENT','선택할 수 없는 의견','PENDING')
                """, id)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO document_bee_suggestion(review_id,type,explanation,decision)
                VALUES(?,'PATCH','원문 없는 수정',null)
                """, id)).isInstanceOf(RuntimeException.class);
    }

    @Test void approvedReferenceChangesRequireFreshReviewBeforeSubmission() {
        var old = review();
        long reference = upload("ERD");
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leader.accountId(), reference);
        code(() -> submit(old), "review_stale");
        var fresh = review();
        assertThat(fresh.referenceHash()).isNotEqualTo(old.referenceHash());
        assertThat(fresh.unavailableReferences()).doesNotContain("ERD");
    }

    @Test void workingCopyMarksReviewStaleWhenApprovedReferencesChange() {
        assertThat(copy().reviewStale()).isFalse();
        review();
        assertThat(copy().reviewStale()).isFalse();
        long reference = upload("ERD");
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leader.accountId(), reference);
        assertThat(copy().reviewStale()).isTrue();
        assertThat(copy().latestReview()).isNotNull();
        var fresh = review();
        assertThat(copy().reviewStale()).isFalse();
        var submission = submit(fresh);
        long other = upload("API_SPEC");
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leader.accountId(), other);
        assertThat(submission).isNotNull();
        // 제출한 뒤에는 팀장 결정을 기다리는 건이라 참고 승인본이 바뀌어도 무효로 표시하지 않는다.
        assertThat(copy().reviewStale()).isFalse();
    }

    @Test void invalidAiResultIsNotCompletedAndFailureIsNotAutomaticallyReissued() {
        doReturn(Map.of("origin", "TEMPLATE", "result", Map.of(), "usage", List.of())).when(gateway).review(anyMap());
        code(() -> reviews.review(author, project.projectId(), document, 1, "invalid"), "invalid_ai_output");
        code(() -> reviews.review(author, project.projectId(), document, 1, "invalid"), "review_unavailable");
        verify(gateway, times(1)).review(anyMap());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=?", Integer.class, project.projectId())).isZero();
    }

    @Test void commentNeedsNoDecisionAndPartialScopeSurvivesSubmissionAndReferenceChanges() {
        doAnswer(call -> result(call.getArgument(0), List.of(Map.of(
                "type", "COMMENT", "step", "BODY", "explanation", "승인된 비교 자료가 없어 본문만 확인했습니다."))))
                .when(gateway).review(anyMap());
        var review = review();
        assertThat(review.contractVersion()).isEqualTo(2);
        assertThat(review.reviewStatus()).isEqualTo("PARTIAL");
        assertThat(review.steps()).hasSize(4);
        var comment = review.suggestions().getFirst();
        assertThat(comment.decision()).isNull();
        assertThat(comment.originalText()).isNull();
        for (String decision : List.of("APPLY", "SKIP")) code(() -> reviews.decideSuggestion(author,
                project.projectId(), document, Long.parseLong(review.id()), Long.parseLong(comment.id()),
                new SuggestionDecisionRequest(1, decision)), "validation_failed");
        var submission = submit(review);
        long reference = upload("ERD");
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leader.accountId(), reference);
        var detail = reviews.submission(leader, project.projectId(), document, Long.parseLong(submission.id()));
        assertThat(detail.review()).isEqualTo(review);
        assertThat(detail.review().unavailableReferences()).contains("ERD");
        assertThatThrownBy(() -> jdbc.update("UPDATE document_bee_review SET result='{}' WHERE id=?", Long.parseLong(review.id())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE document_bee_suggestion SET explanation='changed' WHERE id=?", Long.parseLong(comment.id())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM document_bee_suggestion WHERE id=?", Long.parseLong(comment.id())))
                .isInstanceOf(RuntimeException.class);
    }

    @Test void httpV2SerializesScopeAndRejectsCommentDecisionThenAcceptsPartialSubmission() {
        doAnswer(call -> result(call.getArgument(0), List.of(Map.of("type", "COMMENT", "explanation", "설명형 의견"))))
                .when(gateway).review(anyMap());
        var http = client().login(fixtures().emailOf(author.accountId()), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + project.projectId() + "/documents/" + document;
        var reviewed = http.post(path + "/bee-reviews", "{\"expected_revision\":1}",
                "Origin", "https://app.beez.test", "Idempotency-Key", "http-review");
        assertThat(reviewed.statusCode()).withFailMessage(reviewed.body()).isEqualTo(201);
        var body = DesignJson.JSON.readTree(reviewed.body());
        assertThat(body.path("contract_version").asInt()).isEqualTo(2);
        assertThat(body.path("review_status").asText()).isEqualTo("PARTIAL");
        assertThat(body.path("steps").size()).isEqualTo(4);
        assertThat(body.path("steps").get(0).path("reference_ids").isArray()).isTrue();
        var suggestion = body.path("suggestions").get(0);
        assertThat(suggestion.path("type").asText()).isEqualTo("COMMENT");
        assertThat(suggestion.path("decision").isNull() || suggestion.path("decision").isMissingNode()).isTrue();
        var decision = http.patch(path + "/bee-reviews/" + body.path("id").asText() + "/suggestions/" + suggestion.path("id").asText(),
                "{\"expected_revision\":1,\"decision\":\"APPLY\"}", "Origin", "https://app.beez.test");
        assertThat(decision.statusCode()).isEqualTo(400);
        assertThat(DesignJson.JSON.readTree(decision.body()).path("error").path("code").asText()).isEqualTo("validation_failed");
        var submitted = http.post(path + "/review-submissions",
                "{\"expected_revision\":1,\"review_id\":\"" + body.path("id").asText() + "\"}",
                "Origin", "https://app.beez.test", "Idempotency-Key", "http-submit");
        assertThat(submitted.statusCode()).withFailMessage(submitted.body()).isEqualTo(201);
    }

    @Test void legacyReviewIsReadableAndReplayableButCannotAuthorizeANewSubmission() {
        var input = new HashMap<String, Object>();
        input.put("references", List.of());
        var legacy = Map.of("summary", "이전 검토", "suggestions", List.of(), "unavailable_references", List.of("ERD"));
        String oldHash = DesignJson.hash(List.of());
        long id = jdbc.queryForObject("""
                INSERT INTO document_bee_review(document_id,input_revision,resolved_revision,reference_hash,input,result,usage)
                VALUES(?,1,1,?,cast(? AS jsonb),cast(? AS jsonb),'[]') RETURNING id
                """, Long.class, document, oldHash, DesignJson.canonical(input), DesignJson.canonical(legacy));
        jdbc.update("""
                INSERT INTO document_bee_review_request(document_id,account_id,request_key,fingerprint,status,review_id)
                VALUES(?,?,'legacy',?,'COMPLETED',?)
                """, document, author.accountId(), DesignJson.hash(List.of(1, oldHash)), id);
        var old = copy().latestReview();
        assertThat(old.contractVersion()).isNull();
        assertThat(old.steps()).isEmpty();
        assertThat(reviews.review(author, project.projectId(), document, 1, "legacy")).isEqualTo(old);
        code(() -> submit(old), "review_stale");
        verify(gateway, never()).review(anyMap());
    }

    @Test void missingBodyStageIsRejectedButActualModelUsageIsStillRecorded() {
        doAnswer(call -> {
            var envelope = result(call.getArgument(0), List.of());
            var output = (Map<String, Object>) envelope.get("result");
            ((List<?>) output.get("steps")).removeFirst();
            return envelope;
        }).when(gateway).review(anyMap());
        code(() -> review(), "invalid_ai_output");
        assertThat(copy().latestReview()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND failed=true", Integer.class,
                project.projectId())).isEqualTo(1);
    }

    @Test void sameKindIsExcludedAndApprovedReferencesHaveTransmittedTextHashes() {
        long api = upload("API_SPEC");
        long req = upload("REQUIREMENTS");
        for (long id : List.of(api, req)) jdbc.update(
                "UPDATE document_version SET status='APPROVED',approved_at=now(),approved_by=? WHERE document_id=?", leader.accountId(), id);
        long erd = upload("ERD");
        var result = reviews.review(author, project.projectId(), erd, 1, "same-kind");
        assertThat(result.reviewStatus()).isEqualTo("COMPLETE");
        assertThat(result.steps()).anySatisfy(step -> {
            assertThat(step.step()).isEqualTo("ERD"); assertThat(step.reason()).isEqualTo("SAME_KIND");
        });
        assertThat(result.references()).allSatisfy(ref -> {
            assertThat(ref.status()).isEqualTo("APPROVED");
            assertThat(ref.contentHash()).isEqualTo(com.beez.backend.document.validation.DocumentReviewReferenceHasher.textHash(ref.content()));
        });
        assertThat(reviews.submit(author, project.projectId(), erd,
                new DocumentSubmissionRequest(1, result.id()), "same-kind-submit").status()).isEqualTo("SUBMITTED");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {404, 503})
    void unavailableAiDoesNotCreateReviewOrUsageAndNewKeyCanRetry(int status) {
        doThrow(new org.springframework.web.client.RestClientResponseException(
                "AI unavailable", status, "Unavailable", org.springframework.http.HttpHeaders.EMPTY,
                new byte[0], StandardCharsets.UTF_8)).when(gateway).review(anyMap());
        code(() -> reviews.review(author, project.projectId(), document, 1, "unavailable"), "review_unavailable");
        code(() -> reviews.review(author, project.projectId(), document, 1, "unavailable"), "review_unavailable");
        verify(gateway, times(1)).review(anyMap());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=?", Integer.class, project.projectId())).isZero();
        doAnswer(call -> result(call.getArgument(0), List.of())).when(gateway).review(anyMap());
        assertThat(reviews.review(author, project.projectId(), document, 1, "retry-new-key").id()).isNotBlank();
        verify(gateway, times(2)).review(anyMap());
    }

    @Test void submittedBytesAndHistoryAreProtectedInDatabaseAndReviewDecisionsCannotBeOverwritten() {
        var submission = submit(review());
        assertThatThrownBy(() -> jdbc.update("UPDATE document_version SET content_text='changed' WHERE id=?", Long.parseLong(submission.documentVersionId()))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE document_working_copy SET content_text='changed' WHERE document_id=?", document)).isInstanceOf(RuntimeException.class);
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("APPROVE", null));
        code(() -> reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("REJECT", "뒤집기")), "submission_decided");
        assertThatThrownBy(() -> jdbc.update("UPDATE document_review_submission SET rejection_reason='changed' WHERE id=?", Long.parseLong(submission.id()))).isInstanceOf(RuntimeException.class);
    }

    @Test void reviewRunsOutsideTransactionAndDiscardsResultIfAuthorEditsWhileAiIsRunning() {
        doAnswer(call -> {
            var input = call.<Map<String, Object>>getArgument(0);
            reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "새 내용", "plan.md"));
            return result(input, List.of());
        }).when(gateway).review(anyMap());
        code(() -> reviews.review(author, project.projectId(), document, 1, "late"), "version_conflict");
        assertThat(copy().latestReview()).isNull();
    }

    @Test void concurrentSubmissionWithSameKeyCreatesOneVersionAndCleansUnusedOriginal() throws Exception {
        var review = review();
        var request = new DocumentSubmissionRequest(1, review.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var gate = new CountDownLatch(1);
            var a = pool.submit(() -> { gate.await(); return reviews.submit(author, project.projectId(), document, request, "concurrent"); });
            var b = pool.submit(() -> { gate.await(); return reviews.submit(author, project.projectId(), document, request, "concurrent"); });
            gate.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
        }
        assertThat(copy().submissions()).hasSize(1);
        assertThat(files).hasSize(2); // Original upload plus one final normalized submission.
    }

    @Test void httpRequiresOriginAuthorAndSnakeCaseRequestAndProvidesLeaderQueue() {
        var authorClient = client().login(fixtures().emailOf(author.accountId()), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + project.projectId() + "/documents/" + document;
        String body = "{\"expected_revision\":1,\"content\":\"HTTP edit\",\"file_name\":\"plan.md\"}";
        assertThat(authorClient.patch(path + "/working-copy", body).statusCode()).isEqualTo(403);
        var invalid = authorClient.patch(path + "/working-copy",
                "{\"expected_revision\":0,\"content\":\"\",\"file_name\":\"\"}", "Origin", "https://app.beez.test");
        assertThat(invalid.statusCode()).isEqualTo(400);
        var invalidError = DesignJson.JSON.readTree(invalid.body()).path("error");
        assertThat(invalidError.path("code").asText()).isEqualTo("validation_failed");
        assertThat(invalidError.path("message").asText()).containsPattern("[가-힣]");
        var edited = authorClient.patch(path + "/working-copy", body, "Origin", "https://app.beez.test");
        assertThat(edited.statusCode()).withFailMessage(edited.body()).isEqualTo(200);
        assertThat(DesignJson.JSON.readTree(edited.body()).path("owner_account_id").asText()).isEqualTo(author.accountId().toString());
        var submission = submit(review());
        var leaderClient = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        var queue = leaderClient.get("/api/projects/" + project.projectId() + "/document-review-submissions?status=SUBMITTED");
        assertThat(queue.statusCode()).isEqualTo(200);
        assertThat(queue.body()).contains(submission.id());
    }

    /** FE 스키마는 null 이 아니라 '없음'(키 생략)을 받는다. 실제 HTTP 응답에서 null 필드가 빠지는지 확인한다 (PR 리뷰). */
    @Test void workingCopyHttpResponseOmitsNullReviewRequestFieldsInsteadOfSendingNull() throws Exception {
        var authorClient = client().login(fixtures().emailOf(author.accountId()), AccessFixtures.PASSWORD);
        String path = "/api/projects/" + project.projectId() + "/documents/" + document + "/working-copy";
        var none = DesignJson.JSON.readTree(authorClient.get(path).body());
        assertThat(none.has("bee_review_request")).as("요청이 없으면 키 자체가 없다").isFalse();
        assertThat(none.toString()).doesNotContain(":null");

        failWith(new org.springframework.web.client.ResourceAccessException("read", new java.net.http.HttpTimeoutException("slow")));
        code(() -> reviews.review(author, project.projectId(), document, 1, "http-failed"), "review_timeout");
        var failed = DesignJson.JSON.readTree(authorClient.get(path).body()).path("bee_review_request");
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("error_code").asText()).isEqualTo("review_timeout");
        assertThat(failed.has("updated_at")).isTrue();

        reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "# 줄인 계획\n로그인한다.", "plan.md"));
        doAnswer(call -> result(call.getArgument(0), List.of())).when(gateway).review(anyMap());
        reviews.review(author, project.projectId(), document, 2, "http-ok");
        var completed = DesignJson.JSON.readTree(authorClient.get(path).body()).path("bee_review_request");
        assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.has("error_code")).as("실패가 아니면 error_code 키가 없다").isFalse();
        assertThat(completed.toString()).doesNotContain(":null");
    }

    @Test void reuploadedOriginalBytesSurviveSubmissionAndCleanupKeepsSubmittedFiles() throws Exception {
        byte[] original = "\uFEFF# 파일\r\n다시 올린 원본\r\n".getBytes(StandardCharsets.UTF_8);
        var edited = reviews.reupload(author, project.projectId(), document, 1, "new.md", original);
        assertThat(edited.content()).isEqualTo("# 파일\n다시 올린 원본\n");
        var submission = submit(review());
        assertThat(documents.download(author, project.projectId(), document, 1).bytes()).isEqualTo(original);
        String key = jdbc.queryForObject("SELECT storage_key FROM document_version WHERE id=?", String.class, Long.parseLong(submission.documentVersionId()));
        cleanup.runOnce();
        assertThat(files).containsKey(key);
        assertThat(files.get(key)).isEqualTo(original);
        assertThat(reviews.submission(leader, project.projectId(), document, Long.parseLong(submission.id())).content()).isEqualTo(edited.content());
    }

    @Test void pendingDocumentDecisionCannotBypassChangeProposalAfterBaselineExists() {
        var submission=submit(review());
        long baseline=jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class,project.projectId(),leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?",baseline,project.projectId());
        code(() -> reviews.decide(leader,project.projectId(),document,Long.parseLong(submission.id()),
                new DocumentDecisionRequest("APPROVE",null)),"change_proposal_required");
        assertThat(jdbc.queryForObject("SELECT status FROM document_review_submission WHERE id=?",String.class,Long.parseLong(submission.id())))
                .isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?",String.class,Long.parseLong(submission.documentVersionId())))
                .isEqualTo("DRAFT");
    }

    @Test void fourSeparateApprovalsStillNeedExplicitBaselineButtonAndReadyIndexes() throws Exception {
        var documentIds = List.of(document, upload("REQUIREMENTS"), upload("ERD"), upload("API_SPEC"));
        var submissions = new ArrayList<DocumentSubmissionResponse>();
        for (long id : documentIds) {
            var review = reviews.review(author, project.projectId(), id, 1, "review-" + id);
            submissions.add(reviews.submit(author, project.projectId(), id, new DocumentSubmissionRequest(1, review.id()), "submit-" + id));
        }
        for (var submission : submissions.subList(0, 3)) reviews.decide(leader, project.projectId(), Long.parseLong(submission.documentId()),
                Long.parseLong(submission.id()), new DocumentDecisionRequest("APPROVE", null));
        var item = items.create(leader, project.projectId(), new com.beez.backend.designitem.dto.CreateDesignItemRequest(
                "REQUIREMENT", 1, Map.of("description", "문서별 승인"), null, null), "baseline-item");
        var versions = submissions.stream().map(DocumentSubmissionResponse::documentVersionId).toList();
        var before = new com.beez.backend.designitem.dto.PublishInitialBaselineRequest(Long.toString(project.projectId()), versions,
                List.of(item.id()), itemQueries.list(leader, project.projectId(), "DRAFT", null, null, null, 20).draftSnapshotHash());
        code(() -> baselines.publish(leader, before, "button"), "document_not_approved");
        var last = submissions.getLast();
        reviews.decide(leader, project.projectId(), Long.parseLong(last.documentId()), Long.parseLong(last.id()), new DocumentDecisionRequest("APPROVE", null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM baseline WHERE project_id=?", Integer.class, project.projectId())).isZero();
        var request = new com.beez.backend.designitem.dto.PublishInitialBaselineRequest(Long.toString(project.projectId()), versions,
                List.of(item.id()), itemQueries.list(leader, project.projectId(), "DRAFT", null, null, null, 20).draftSnapshotHash());
        code(() -> baselines.publish(leader, request, "button"), "analysis_not_completed");
        for (String version : versions) {
            long id = Long.parseLong(version);
            jdbc.update("UPDATE document_processing SET status='COMPLETED',source_status='READY',index_status='READY' WHERE document_version_id=?", id);
            jdbc.update("""
                    INSERT INTO document_index_manifest(document_version_id,attempt,text_hash,embedding_profile_id,chunking_profile_id,parser_version,chunk_count)
                    SELECT document_version_id,attempt,text_hash,'review-test','test','test',1 FROM document_processing WHERE document_version_id=?
                    """, id);
            jdbc.update("INSERT INTO document_chunk(document_version_id,chunk_no,text,embedding) VALUES(?,0,'text',cast(? AS vector))", id,
                    "[1," + "0,".repeat(1022) + "0]");
        }
        assertThat(baselines.publish(leader, request, "button").documents()).hasSize(4);
        assertThat(baselines.publish(leader, request, "button").versionNo()).isEqualTo(1);
        // 같은 키 재요청(replay)은 알림을 늘리지 않는다. 최초 기준 알림은 팀장을 뺀 활성 팀원에게 한 번씩만 간다.
        assertThat(notifications("BASELINE_CREATED")).singleElement().satisfies(row -> {
            assertThat(row.get("recipient_account_id")).isEqualTo(author.accountId());
            assertThat(row.get("ref_kind")).isEqualTo("BASELINE");
        });
        // 기준 활동도 알림처럼 재생에서 늘지 않고 한 번만 남는다. 행위자는 발행한 팀장, 기준 필터용 baseline_id 가 채워진다.
        assertThat(activities("BASELINE_CREATED")).singleElement().satisfies(row -> {
            assertThat(row.get("actor_id")).isEqualTo(leader.accountId());
            assertThat(row.get("ref_kind")).isEqualTo("BASELINE");
            assertThat(row.get("baseline_id")).isEqualTo(row.get("ref_id"));
            assertThat(row.get("dedupe_key")).isEqualTo("baseline:" + row.get("ref_id") + ":created");
        });
        code(() -> reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "우회 변경", "plan.md")), "change_proposal_required");
        assertThat(reviews.decide(leader, project.projectId(), Long.parseLong(last.documentId()), Long.parseLong(last.id()),
                new DocumentDecisionRequest("APPROVE", null)).status()).isEqualTo("APPROVED");
    }

    // ---- S15P31A108-382: 시간 초과 구분, 실패 상태 노출, 문서 등록 취소 ----

    void failWith(Throwable failure) {
        if (failure instanceof RuntimeException runtime) doThrow(runtime).when(gateway).review(anyMap());
    }

    void timeoutOf(org.springframework.web.client.RestClientResponseException failure, String key) {
        failWith(failure);
        assertThatThrownBy(() -> reviews.review(author, project.projectId(), document, 1, key))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("review_timeout");
                    assertThat(e.getStatus()).isEqualTo(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT);
                });
    }

    @Test void aiTimeoutIsReportedAsReviewTimeoutWhileOtherFailuresStayUnavailable() {
        timeoutOf(new org.springframework.web.client.RestClientResponseException("timeout", 504, "Gateway Timeout",
                org.springframework.http.HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8), "t-504");
        assertThat(jdbc.queryForObject("SELECT error_code FROM document_bee_review_request WHERE document_id=? AND request_key='t-504'",
                String.class, document)).isEqualTo("review_timeout");
        // 같은 키로 다시 부르면 AI 를 부르지 않고 같은 시간 초과 코드로 답한다.
        assertThatThrownBy(() -> reviews.review(author, project.projectId(), document, 1, "t-504"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("review_timeout"));
        verify(gateway, times(1)).review(anyMap());
        // model_timeout 코드는 상태가 504 가 아니어도 시간 초과다. 502 model_unavailable 은 장애다.
        timeoutOf(new org.springframework.web.client.RestClientResponseException("timeout", 502, "Bad Gateway",
                org.springframework.http.HttpHeaders.EMPTY, "{\"error\":{\"code\":\"model_timeout\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8), "t-body");
        failWith(new org.springframework.web.client.RestClientResponseException("down", 502, "Bad Gateway",
                org.springframework.http.HttpHeaders.EMPTY, "{\"error\":{\"code\":\"model_unavailable\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
        code(() -> reviews.review(author, project.projectId(), document, 1, "t-502"), "review_unavailable");
    }

    @Test void readTimeoutIsReviewTimeoutButConnectTimeoutIsUnavailable() {
        failWith(new org.springframework.web.client.ResourceAccessException("read",
                new java.net.http.HttpTimeoutException("request timed out")));
        code(() -> reviews.review(author, project.projectId(), document, 1, "read"), "review_timeout");
        failWith(new org.springframework.web.client.ResourceAccessException("connect",
                new java.net.http.HttpConnectTimeoutException("connect timed out")));
        code(() -> reviews.review(author, project.projectId(), document, 1, "connect"), "review_unavailable");
        failWith(new org.springframework.web.client.ResourceAccessException("refused", new java.net.ConnectException("refused")));
        code(() -> reviews.review(author, project.projectId(), document, 1, "refused"), "review_unavailable");
    }

    @Test void failedReviewRequestIsVisibleToEveryoneUntilTheDocumentChanges() {
        assertThat(copy().beeReviewRequest()).isNull();
        failWith(new org.springframework.web.client.ResourceAccessException("read",
                new java.net.SocketTimeoutException("Read timed out")));
        code(() -> reviews.review(author, project.projectId(), document, 1, "visible"), "review_timeout");
        // 올린 사람이 아닌 팀원(팀장)도 같은 상태를 읽는다
        var seen = reviews.get(leader, project.projectId(), document).beeReviewRequest();
        assertThat(seen.status()).isEqualTo("FAILED");
        assertThat(seen.errorCode()).isEqualTo("review_timeout");
        // 고치면 새 리비전의 요청이 아직 없으므로 비어 보인다
        reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "# 줄인 계획\n로그인한다.", "plan.md"));
        assertThat(copy().beeReviewRequest()).isNull();
        doAnswer(call -> result(call.getArgument(0), List.of())).when(gateway).review(anyMap());
        reviews.review(author, project.projectId(), document, 2, "after-edit");
        assertThat(copy().beeReviewRequest().status()).isEqualTo("COMPLETED");
        // 10분 넘게 도는 요청은 끝없이 기다리지 않게 실패로 보인다
        jdbc.update("UPDATE document_bee_review_request SET status='RUNNING',error_code=NULL,created_at=now()+interval '1 second' WHERE document_id=?", document);
        assertThat(copy().beeReviewRequest().status()).isEqualTo("RUNNING");
        jdbc.update("UPDATE document_working_copy SET updated_at=now()-interval '1 hour' WHERE document_id=?", document);
        jdbc.update("UPDATE document_bee_review_request SET created_at=now()-interval '11 minutes' WHERE document_id=?", document);
        var stuck = copy().beeReviewRequest();
        assertThat(stuck.status()).isEqualTo("FAILED");
        assertThat(stuck.errorCode()).isEqualTo("review_timeout");
    }

    long member() {
        long account = fixtures().account();
        fixtures().member(project.projectId(), account, ProjectRole.MEMBER);
        return account;
    }

    @Test void authorDiscardsFailedDocumentAndTheSameKindCanBeRegisteredAgain() {
        var other = CallerContext.human(member());
        failWith(new org.springframework.web.client.ResourceAccessException("read", new java.net.http.HttpTimeoutException("slow")));
        code(() -> reviews.review(author, project.projectId(), document, 1, "slow"), "review_timeout");
        // 올린 사람도 팀장도 아닌 팀원은 취소할 수 없다
        code(() -> reviews.discard(other, project.projectId(), document, "other"), "forbidden");
        var discarded = reviews.discard(author, project.projectId(), document, "discard-1");
        assertThat(discarded.discarded()).isTrue();
        assertThat(reviews.discard(author, project.projectId(), document, "discard-1")).isEqualTo(discarded);
        // 다른 키로 다시 취소해도 같은 결과(멱등)이고 활동은 한 번만 남는다
        assertThat(reviews.discard(leader, project.projectId(), document, "discard-2")).isEqualTo(discarded);
        assertThat(activities("DOCUMENT_DISCARDED")).hasSize(1);
        // 폐기된 문서는 목록에서 사라지고 편집·검토·조회는 없는 문서처럼 막힌다. 이력(행)은 남는다
        assertThat(documents.list(author, project.projectId(), null, null, null, null, 20).get("items")).asList().isEmpty();
        code(() -> reviews.get(author, project.projectId(), document), "not_found");
        code(() -> reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "x", "plan.md")), "not_found");
        assertThat(jdbc.queryForObject("SELECT discarded_at IS NOT NULL FROM document WHERE id=?", Boolean.class, document)).isTrue();
        assertThat(jdbc.queryForList("SELECT status FROM document_version WHERE document_id=?", String.class, document)).containsOnly("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review_request WHERE document_id=? AND status='RUNNING'", Integer.class, document)).isZero();
        // 같은 종류를 새로 올릴 수 있다
        long again = upload("PLAN");
        assertThat(again).isNotEqualTo(document);
        assertThat(reviews.get(author, project.projectId(), again).status()).isEqualTo("EDITING");
    }

    @Test void leaderCanDiscardAnotherMembersDocument() {
        assertThat(reviews.discard(leader, project.projectId(), document, "leader").discarded()).isTrue();
        assertThat(activities("DOCUMENT_DISCARDED")).singleElement().satisfies(row -> assertThat(row.get("actor_id")).isEqualTo(leader.accountId()));
    }

    @Test void submittedOrApprovedDocumentCannotBeDiscardedAndSubmissionIsKept() {
        var submission = submit(review());
        code(() -> reviews.discard(author, project.projectId(), document, "locked"), "submission_locked");
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("APPROVE", null));
        code(() -> reviews.discard(author, project.projectId(), document, "approved"), "document_approved");
        code(() -> reviews.discard(leader, project.projectId(), document, "approved-leader"), "document_approved");
        assertThat(jdbc.queryForObject("SELECT discarded_at IS NULL FROM document WHERE id=?", Boolean.class, document)).isTrue();
        assertThat(activities("DOCUMENT_DISCARDED")).isEmpty();
    }

    @Test void rejectedDocumentCanBeDiscardedAfterWithdrawnOrRejected() {
        var submission = submit(review());
        reviews.decide(leader, project.projectId(), document, Long.parseLong(submission.id()), new DocumentDecisionRequest("REJECT", "다시 써 주세요."));
        assertThat(reviews.discard(author, project.projectId(), document, "after-reject").discarded()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_review_submission WHERE document_id=?", Integer.class, document)).isOne();
    }

    @Test void discardIsRefusedAfterTheFirstBaselineAndNeverHidesTheWorkingCopyOfOthers() {
        long baseline = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,1,?) RETURNING id",
                Long.class, project.projectId(), leader.accountId());
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", baseline, project.projectId());
        code(() -> reviews.discard(author, project.projectId(), document, "late"), "change_proposal_required");
        assertThat(reviews.get(author, project.projectId(), document).status()).isEqualTo("EDITING");
    }

    @Autowired com.beez.backend.designitem.repository.BaselineRepository baselineRepository;

    @Test void discardedDocumentNoLongerBlocksTheBaselineReadiness() {
        long second = upload("REQUIREMENTS");
        assertThat(baselineRepository.hasUnapprovedWorkingCopies(project.projectId())).isTrue();
        reviews.discard(author, project.projectId(), document, "d1");
        reviews.discard(author, project.projectId(), second, "d2");
        assertThat(baselineRepository.hasUnapprovedWorkingCopies(project.projectId())).isFalse();
        assertThat(baselineRepository.workingCopyStates(project.projectId())).isEmpty();
    }

    @Test void reviewResultThatArrivesAfterDiscardIsNotAdopted() {
        doAnswer(call -> {
            reviews.discard(author, project.projectId(), document, "mid-review");
            return result(call.<Map<String, Object>>getArgument(0), List.of());
        }).when(gateway).review(anyMap());
        assertThatThrownBy(() -> reviews.review(author, project.projectId(), document, 1, "late-result")).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
    }

    @Test void reviewsOfDifferentDocumentsRunInParallelWithoutBlockingEachOther() throws Exception {
        var ids = List.of(document, upload("REQUIREMENTS"), upload("ERD"), upload("API_SPEC"));
        // 모델 호출을 0.8초 걸리게 한 대역. 직렬이면 3.2초 이상, 병렬이면 1~1.5초 안팎이다.
        doAnswer(call -> {
            Thread.sleep(800);
            return result(call.getArgument(0), List.of());
        }).when(gateway).review(anyMap());
        var pool = Executors.newFixedThreadPool(ids.size());
        try {
            long start = System.nanoTime();
            var futures = ids.stream().map(id -> pool.submit(() ->
                    reviews.review(author, project.projectId(), id, 1, "parallel-" + id).id())).toList();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS)).isNotBlank();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // 직렬이면 모델 대역만으로 3.2초 이상이다. 느린 CI 에서도 병렬이면 넘지 않도록 직렬 하한보다 조금 아래로 잡는다.
            assertThat(elapsedMs).as("서로 다른 문서의 검토가 직렬로 돌았다").isLessThan(3_000);
        } finally {
            pool.shutdownNow();
        }
    }
}
