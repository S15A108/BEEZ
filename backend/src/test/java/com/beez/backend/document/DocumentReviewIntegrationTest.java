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
        var output = new HashMap<String, Object>();
        for (String field : List.of("document_id", "revision", "reference_hash", "request_key")) output.put(field, input.get(field));
        output.put("summary", "문서 검토 완료"); output.put("suggestions", suggestions);
        return Map.of("origin", "MODEL", "result", output, "usage", List.of(Map.of(
                "request_key", input.get("request_key"), "feature", "BEE_DRAFT", "model", "fixture-model",
                "latency_ms", 1, "failed", false, "tokens_known", false)));
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
        code(() -> submit(first), "review_stale");
        code(() -> reviews.review(author, project.projectId(), document, 2, "same"), "idempotency_conflict");
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

    @Test void invalidAiResultIsNotCompletedAndFailureIsNotAutomaticallyReissued() {
        doReturn(Map.of("origin", "TEMPLATE", "result", Map.of(), "usage", List.of())).when(gateway).review(anyMap());
        code(() -> reviews.review(author, project.projectId(), document, 1, "invalid"), "invalid_ai_output");
        code(() -> reviews.review(author, project.projectId(), document, 1, "invalid"), "review_unavailable");
        verify(gateway, times(1)).review(anyMap());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_bee_review WHERE document_id=?", Integer.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=?", Integer.class, project.projectId())).isZero();
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE project_id=?", Integer.class,
                project.projectId())).isZero();
        code(() -> reviews.edit(author, project.projectId(), document, new WorkingCopyUpdateRequest(1, "우회 변경", "plan.md")), "change_proposal_required");
        assertThat(reviews.decide(leader, project.projectId(), Long.parseLong(last.documentId()), Long.parseLong(last.id()),
                new DocumentDecisionRequest("APPROVE", null)).status()).isEqualTo("APPROVED");
    }
}
