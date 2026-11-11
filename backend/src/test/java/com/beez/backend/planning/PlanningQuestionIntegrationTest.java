package com.beez.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.document.service.DocumentApplicationService;
import com.beez.backend.planning.dto.AnswerRequest;
import com.beez.backend.planning.service.PlanningQuestionGenerator;
import com.beez.backend.planning.service.PlanningQuestionService;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.AccessFixtures;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLN-A03 질문 목록, PLN-A04 답변, 133 질문 생성(분석 직후 자동). AI 는 {@link AiClient} 대역, 나머지는 실제다.
 * 핵심: 질문 생성이 실패해도 문서 분석 상태는 그대로이고, 재분석해도 답변된 질문은 보존된다.
 */
class PlanningQuestionIntegrationTest extends PlanningTestSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired PlanningQuestionGenerator generator;
    @Autowired PlanningQuestionService questions;
    @Autowired DocumentApplicationService documents;
    @MockitoBean AiClient ai;

    @BeforeEach void stubAi() {
        doAnswer(call -> questionsResponse("a1", "a2", "a3")).when(ai).planningQuestions(any());
    }

    long[] ids() {
        return jdbc.queryForList("SELECT id FROM planning_question WHERE document_version_id=? AND status<>'SUPERSEDED' ORDER BY rank", Long.class, version)
                .stream().mapToLong(Long::longValue).toArray();
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> items(Map<String, Object> page) { return (List<Map<String, Object>>) page.get("items"); }

    // ---- 질문 생성 ----

    @Test void generatesQuestionsAfterAnalysisAndRecordsUsageWithoutTouchingAnalysis() {
        assertThat(generator.runOnce()).isTrue();
        assertThat(generator.runOnce()).as("같은 버전을 두 번 만들지 않는다").isFalse();

        assertThat(count("SELECT count(*) FROM planning_question WHERE document_version_id=?", version)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM planning_question_run WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='DOCUMENT_PARSE' AND NOT failed", project)).isEqualTo(1);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass((Class) Map.class);
        verify(ai).planningQuestions(sent.capture());
        assertThat(sent.getValue()).containsEntry("project_id", "" + project).containsEntry("kind", "PLAN")
                .containsEntry("media_type", "text/markdown").containsEntry("parser_profile", "markdown-v1")
                .containsEntry("text_hash", TEXT_HASH).containsEntry("content_hash", CONTENT_HASH)
                .containsEntry("content", CONTENT).containsEntry("mode", "MODEL");
        // 저장 값은 sha256: 접두어가 붙지만 AI 스키마는 접두어 없는 hex 64자만 받는다(S15P31A108-373).
        assertThat((String) sent.getValue().get("content_hash")).matches(HEX64);
        assertThat((String) sent.getValue().get("text_hash")).matches(HEX64);
    }

    @Test void skipsDocumentsOnceAnInitialBaselineExists() {
        publishBaseline();
        assertThat(generator.runOnce()).isFalse();
        assertThat(count("SELECT count(*) FROM planning_question_run WHERE document_version_id=?", version)).isZero();
    }

    @Test void aiFailureLeavesAnalysisCompletedAndRetryRunsOnlyQuestionGeneration() {
        doThrow(new ResourceAccessException("down", new SocketTimeoutException("slow"))).when(ai).planningQuestions(any());
        assertThat(generator.runOnce()).isTrue();

        // 재시도 가능한 첫 실패는 FAILED 전에 한 번 더 돈다. 기다리는 동안 화면에는 오류 없이 PENDING 이다 (S15P31A108-396).
        assertThat(jdbc.queryForObject("SELECT status FROM planning_question_run WHERE document_version_id=?", String.class, version)).isEqualTo("PENDING");
        assertThat(documents.analysis(leaderCaller, project, document, version).get("question_generation")).isEqualTo(Map.of("status", "PENDING"));
        assertThat(generator.runOnce()).as("자동 재시도").isTrue();

        assertThat(jdbc.queryForMap("SELECT status,error_code,retryable FROM planning_question_run WHERE document_version_id=?", version))
                .containsEntry("status", "FAILED").containsEntry("error_code", "ai_timeout").containsEntry("retryable", true);
        assertThat(jdbc.queryForObject("SELECT status FROM document_processing WHERE document_version_id=?", String.class, version)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM planning_question WHERE document_version_id=?", version)).isZero();
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='DOCUMENT_PARSE' AND failed", project)).isEqualTo(2);
        assertThat(generator.runOnce()).as("자동 재시도는 한 번뿐이다").isFalse();

        var status = documents.analysis(leaderCaller, project, document, version);
        assertThat(status).containsEntry("status", "COMPLETED");
        assertThat(status.get("question_generation")).isEqualTo(Map.of("status", "FAILED", "error_code", "ai_timeout", "retryable", true));

        // PLN-A02: 분석은 끝났고 질문 생성만 실패했으면 질문 생성만 다시 대기시킨다.
        doAnswer(call -> questionsResponse("a1", "a2", "a3")).when(ai).planningQuestions(any());
        var retried = documents.retry(leaderCaller, project, document, version, "retry-1");
        assertThat(retried.get("question_generation")).isEqualTo(Map.of("status", "PENDING"));
        assertThat(generator.runOnce()).isTrue();
        assertThat(count("SELECT count(*) FROM planning_question WHERE document_version_id=?", version)).isEqualTo(3);
    }

    @Test void retryDoesNotRequeueFailedQuestionGenerationThatNoWorkerWillPick() {
        doThrow(new ResourceAccessException("down", new SocketTimeoutException("slow"))).when(ai).planningQuestions(any());
        assertThat(generator.runOnce()).isTrue();
        assertThat(generator.runOnce()).as("자동 재시도까지 실패").isTrue();
        publishBaseline();

        // 기준이 발행된 뒤에는 워커가 질문을 만들지 않는다. PENDING 으로 되돌리면 화면이 '만드는 중'으로 영원히 남는다 (S15P31A108-359).
        assertThatThrownBy(() -> documents.retry(leaderCaller, project, document, version, "retry-after-baseline"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.getCode()).isEqualTo("question_generation_unavailable"));
        assertThat(jdbc.queryForObject("SELECT status FROM planning_question_run WHERE document_version_id=?", String.class, version)).isEqualTo("FAILED");
        assertThat(generator.runOnce()).isFalse();
    }

    @Test void outputCapTruncationIsReportedAsItsOwnCodeAndStaysRetryable() {
        // AI 가 모델 답이 출력 상한에서 끊겼다고 알리면 일반 장애(ai_unavailable)가 아니라 상한 문제로 남긴다 (S15P31A108-382).
        doThrow(org.springframework.web.client.HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "cut", org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":{\"code\":\"model_output_truncated\"}}".getBytes(), java.nio.charset.StandardCharsets.UTF_8)).when(ai).planningQuestions(any());
        assertThat(generator.runOnce()).isTrue();
        assertThat(generator.runOnce()).as("자동 재시도까지 실패").isTrue();
        assertThat(jdbc.queryForMap("SELECT status,error_code,retryable FROM planning_question_run WHERE document_version_id=?", version))
                .containsEntry("status", "FAILED").containsEntry("error_code", "ai_output_too_long").containsEntry("retryable", true);
        // 형식 오류로 거절된 같은 502 는 그대로 일반 장애다.
        jdbc.update("DELETE FROM planning_question_run");
        doThrow(org.springframework.web.client.HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "bad", org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":{\"code\":\"model_unavailable\"}}".getBytes(), java.nio.charset.StandardCharsets.UTF_8)).when(ai).planningQuestions(any());
        generator.runOnce();
        assertThat(jdbc.queryForObject("SELECT error_code FROM planning_question_run WHERE document_version_id=?", String.class, version))
                .isEqualTo("ai_unavailable");
    }

    @Test void rejectedRequestIsNotRetryableAndMismatchedOutputIsRejectedWholesale() {
        doThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "bad", org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":{\"code\":\"invalid_ai_input\"}}".getBytes(), java.nio.charset.StandardCharsets.UTF_8)).when(ai).planningQuestions(any());
        generator.runOnce();
        assertThat(jdbc.queryForMap("SELECT error_code,retryable FROM planning_question_run WHERE document_version_id=?", version))
                .containsEntry("error_code", "ai_unavailable").containsEntry("retryable", false);

        jdbc.update("DELETE FROM planning_question_run");
        doAnswer(call -> {
            var response = questionsResponse("a1");
            ((Map<String, Object>) response.get("result")).put("text_hash", "0".repeat(64));
            return response;
        }).when(ai).planningQuestions(any());
        generator.runOnce();
        generator.runOnce();
        assertThat(jdbc.queryForMap("SELECT status,error_code FROM planning_question_run WHERE document_version_id=?", version))
                .containsEntry("status", "FAILED").containsEntry("error_code", "invalid_ai_output");
        assertThat(count("SELECT count(*) FROM planning_question WHERE document_version_id=?", version)).isZero();
    }

    @Test void reanalysisKeepsAnsweredQuestionsSupersedesOpenOnesAndAddsNewOnes() {
        generator.runOnce();
        long[] first = ids();
        questions.answer(leaderCaller, project, first[0], new AnswerRequest("기존 답", null, null));
        // a2 는 미답변 → 새 결과에 없으면 숨김, a3 는 새 결과에도 있어 유지, a4 는 새로 생김
        doAnswer(call -> questionsResponse("a1", "a3", "a4")).when(ai).planningQuestions(any());
        jdbc.update("UPDATE document_processing SET attempt=2 WHERE document_version_id=?", version);
        assertThat(generator.runOnce()).isTrue();

        var rows = jdbc.queryForList("SELECT question_key,status,answer FROM planning_question WHERE document_version_id=? ORDER BY id", version);
        assertThat(rows).extracting(r -> r.get("question_key") + ":" + r.get("status"))
                .containsExactly("q-a1:ANSWERED", "q-a2:SUPERSEDED", "q-a3:OPEN", "q-a4:OPEN");
        assertThat(rows.get(0).get("answer")).isEqualTo("기존 답");
        fails(HttpStatus.CONFLICT, "question_superseded", () -> questions.answer(leaderCaller, project, first[1], new AnswerRequest("답", null, null)));
        assertThat(items(questions.list(leaderCaller, project, version, null, null))).hasSize(3);
    }

    // S15P31A108-396: 본문이 바뀌어 질문을 다시 만드는 동안이나 그러지 못했을 때 이전 본문으로 만든 미답변 질문이
    // 최신처럼 보이고 답할 수 있었다. 갱신 상태를 내려주고, 미답변만 막고 답한 이력은 그대로 둔다.
    @Test void outdatedQuestionsAreMarkedAndOpenOnesCannotBeAnsweredUntilRegenerated() {
        generator.runOnce();
        long[] id = ids();
        questions.answer(leaderCaller, project, id[0], new AnswerRequest("기존 답", null, null));
        assertThat(items(questions.list(leaderCaller, project, version, null, null))).extracting(i -> i.get("freshness")).containsOnly("CURRENT");

        jdbc.update("UPDATE document_processing SET attempt=2 WHERE document_version_id=?", version);
        doThrow(new ResourceAccessException("down", new SocketTimeoutException("slow"))).when(ai).planningQuestions(any());
        assertThat(items(questions.list(leaderCaller, project, version, null, null))).extracting(i -> i.get("freshness")).containsOnly("REFRESHING");
        fails(HttpStatus.CONFLICT, "question_outdated", () -> questions.answer(leaderCaller, project, id[1], new AnswerRequest("답", null, null)));
        fails(HttpStatus.CONFLICT, "question_outdated", () -> questions.answer(leaderCaller, project, id[1], new AnswerRequest(null, null, "DISMISSED")));
        assertThat(questions.answer(leaderCaller, project, id[0], new AnswerRequest("고친 답", null, null))).as("답한 질문은 이력이라 고칠 수 있다")
                .containsEntry("answer", "고친 답").containsEntry("freshness", "REFRESHING");

        generator.runOnce();
        generator.runOnce();
        assertThat(items(questions.list(leaderCaller, project, version, null, null))).extracting(i -> i.get("freshness")).containsOnly("REFRESH_FAILED");
        fails(HttpStatus.CONFLICT, "question_outdated", () -> questions.answer(leaderCaller, project, id[1], new AnswerRequest("답", null, null)));

        doAnswer(call -> questionsResponse("a1", "a2", "a3")).when(ai).planningQuestions(any());
        documents.retry(leaderCaller, project, document, version, "retry-396");
        generator.runOnce();
        assertThat(items(questions.list(leaderCaller, project, version, null, null))).extracting(i -> i.get("freshness")).containsOnly("CURRENT");
        assertThat(questions.answer(leaderCaller, project, id[1], new AnswerRequest("답", null, null))).containsEntry("status", "ANSWERED");
    }

    // 반려 뒤 다시 낸 새 버전이 생기면 이전 버전 질문도 새 버전의 갱신 상태를 따르고, 새 버전 질문이 만들어지면 미답변은
    // 조회할 때 대체로 판정돼 목록·개수에서 빠진다. 행은 바꾸지 않으므로 새 버전이 반려되면 이전 버전 질문이 다시 보인다.
    @Test void olderVersionQuestionsFollowTheCurrentVersionAndComeBackWhenItIsRejected() {
        long first = version;
        generator.runOnce();
        long[] id = ids();
        questions.answer(leaderCaller, project, id[0], new AnswerRequest("기존 답", null, null));
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_by=?,approved_at=now() WHERE id=?", leader, first);
        version = documentVersion(document, 2, CONTENT);

        assertThat(items(questions.list(leaderCaller, project, null, null, null))).extracting(i -> i.get("freshness")).containsOnly("REFRESHING");
        fails(HttpStatus.CONFLICT, "question_outdated", () -> questions.answer(leaderCaller, project, id[1], new AnswerRequest("답", null, null)));

        assertThat(generator.runOnce()).as("새 버전 질문 생성").isTrue();
        var page = questions.list(leaderCaller, project, null, null, null);
        assertThat(items(page)).extracting(i -> i.get("id")).as("이전 버전은 답한 질문만 남는다")
                .contains("" + id[0]).doesNotContain("" + id[1], "" + id[2]);
        assertThat(items(page)).extracting(i -> i.get("freshness")).containsOnly("CURRENT");
        assertThat(page.get("total_count")).isEqualTo(4L);
        assertThat(items(page)).extracting(i -> i.get("number")).containsExactly(1L, 2L, 3L, 4L);
        fails(HttpStatus.CONFLICT, "question_superseded", () -> questions.answer(leaderCaller, project, id[1], new AnswerRequest("답", null, null)));

        jdbc.update("UPDATE document_version SET status='REJECTED' WHERE id=?", version);
        assertThat(items(questions.list(leaderCaller, project, first, null, null))).extracting(i -> i.get("id"))
                .as("새 버전이 반려되면 승인된 이전 버전의 질문이 다시 보인다").containsExactly("" + id[0], "" + id[1], "" + id[2]);
        assertThat(questions.answer(leaderCaller, project, id[1], new AnswerRequest("답", null, null))).containsEntry("status", "ANSWERED");
    }

    // 자동 재시도를 기다리는 사이 기준이 발행되면 워커가 잡지 않으므로 그 실패가 마지막이다 — '만드는 중'으로 남기지 않는다(S15P31A108-359).
    @Test void autoRetryWaitingWhenTheBaselineIsPublishedShowsAsFailed() {
        doThrow(new ResourceAccessException("down", new SocketTimeoutException("slow"))).when(ai).planningQuestions(any());
        generator.runOnce();
        publishBaseline();

        assertThat(documents.analysis(leaderCaller, project, document, version).get("question_generation"))
                .isEqualTo(Map.of("status", "FAILED", "error_code", "ai_timeout", "retryable", true));
        fails(HttpStatus.CONFLICT, "question_generation_unavailable", () -> documents.retry(leaderCaller, project, document, version, "retry-after-baseline-396"));
    }

    // 자동 재시도를 기다리는 사이 문서가 다시 분석되면 새 본문의 첫 실행이므로 자동 재시도 한 번을 새로 받는다.
    @Test void reanalysisWhileAutoRetryIsWaitingGivesTheNewTextItsOwnRetry() {
        doThrow(new ResourceAccessException("down", new SocketTimeoutException("slow"))).when(ai).planningQuestions(any());
        generator.runOnce();
        jdbc.update("UPDATE document_processing SET attempt=2 WHERE document_version_id=?", version);

        generator.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM planning_question_run WHERE document_version_id=?", String.class, version)).isEqualTo("PENDING");
        generator.runOnce();
        assertThat(jdbc.queryForObject("SELECT status FROM planning_question_run WHERE document_version_id=?", String.class, version)).isEqualTo("FAILED");
    }

    @Test void answeredQuestionTextIsNotOverwrittenByReanalysis() {
        generator.runOnce();
        long a1 = ids()[0];
        questions.answer(leaderCaller, project, a1, new AnswerRequest("답", null, null));
        doAnswer(call -> {
            var response = questionsResponse("a1");
            ((List<Map<String, Object>>) ((Map<String, Object>) response.get("result")).get("questions")).get(0).put("text", "문구가 바뀐 질문");
            return response;
        }).when(ai).planningQuestions(any());
        jdbc.update("UPDATE document_processing SET attempt=2 WHERE document_version_id=?", version);
        generator.runOnce();
        assertThat(jdbc.queryForObject("SELECT question FROM planning_question WHERE id=?", String.class, a1)).isEqualTo("a1 질문입니다.");
    }

    // ---- PLN-A03 목록 ----

    @Test void listsWithNumberTotalCountAndCursor() {
        generator.runOnce();
        var page1 = questions.list(memberCaller, project, version, null, 2);
        assertThat(items(page1)).extracting(i -> i.get("number")).containsExactly(1L, 2L);
        assertThat(page1).containsEntry("total_count", 3L).containsEntry("has_more", true);
        long secondId = Long.parseLong(((Map<String, Object>) items(page1).get(1)).get("id").toString());
        assertThat((String) page1.get("next_cursor")).isEqualTo(String.valueOf(secondId));
        var first = items(page1).get(0);
        assertThat(first).containsEntry("question_type", "ESSAY").containsEntry("status", "OPEN").containsEntry("evidence_status", "REVIEW_CANDIDATE");
        assertThat(first.get("source")).isEqualTo(Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1, "location", "기획서 / L3"));
        var page2 = questions.list(memberCaller, project, version, (String) page1.get("next_cursor"), 2);
        assertThat(items(page2)).extracting(i -> i.get("number")).containsExactly(3L);
        assertThat(page2).containsEntry("has_more", false).doesNotContainKey("next_cursor");
    }

    @Test void cursorIsASortKeyNotAPositionSoChangesBetweenPagesNeitherRepeatNorSkip() {
        generator.runOnce();
        var page1 = questions.list(memberCaller, project, version, null, 2);
        var shown = items(page1).stream().map(i -> i.get("id")).toList();
        // 쪽을 넘기는 사이 앞쪽 질문이 SUPERSEDED 된다
        jdbc.update("UPDATE planning_question SET status='SUPERSEDED' WHERE id=?", Long.parseLong(shown.get(0).toString()));
        var page2 = questions.list(memberCaller, project, version, (String) page1.get("next_cursor"), 2);
        assertThat(items(page2)).hasSize(1);
        assertThat(items(page2).get(0).get("id")).isNotIn(shown);
        assertThat(page2).containsEntry("total_count", 2L).containsEntry("has_more", false);
    }

    @Test void rankChangesBetweenPagesNeitherRepeatNorSkip() {
        generator.runOnce();
        var page1 = questions.list(memberCaller, project, version, null, 2);
        var shown = items(page1).stream().map(i -> i.get("id").toString()).toList();
        var all = jdbc.queryForList("SELECT id FROM planning_question WHERE document_version_id=? AND status<>'SUPERSEDED' ORDER BY id", Long.class, version);
        long unseen = all.stream().filter(id -> !shown.contains(id.toString())).findFirst().orElseThrow();
        // 쪽 사이에 재분석이 rank 를 바꾼다: 안 본 질문은 맨 앞으로, 이미 본 질문은 맨 뒤로
        jdbc.update("UPDATE planning_question SET rank=1 WHERE id=?", unseen);
        jdbc.update("UPDATE planning_question SET rank=999 WHERE id=?", Long.parseLong(shown.get(0)));
        var page2 = questions.list(memberCaller, project, version, (String) page1.get("next_cursor"), 5);
        assertThat(items(page2)).extracting(i -> i.get("id").toString()).containsExactly(String.valueOf(unseen));
        assertThat(page2).containsEntry("has_more", false);
    }

    @Test void questionAddedToAnEarlierDocumentVersionWhilePagingIsStillReturnedOnTheNextPage() {
        generator.runOnce();
        long newer = documentVersion(document, 2, "# 두 번째 버전");
        var all = jdbc.queryForList("SELECT id FROM planning_question WHERE document_version_id=? ORDER BY id", Long.class, version);
        // 앞 두 질문은 새 버전(커서가 문서 버전 순이었다면 뒤쪽)에 둔다
        jdbc.update("UPDATE planning_question SET document_version_id=? WHERE id IN (?,?)", newer, all.get(0), all.get(1));
        var page1 = questions.list(memberCaller, project, null, null, 2);
        var shown = items(page1).stream().map(i -> i.get("id").toString()).toList();
        // 쪽 사이에 이전 문서 버전에 새 질문이 생긴다(비동기 생성·재분석)
        long added = jdbc.queryForObject("""
                INSERT INTO planning_question(project_id,document_version_id,kind,question,origin,question_key,question_type,answer_format,evidence_status,evidence,rank)
                SELECT project_id,?,kind,'나중에 생긴 질문입니다.',origin,'q-late',question_type,answer_format,evidence_status,evidence,rank
                  FROM planning_question WHERE id=? RETURNING id
                """, Long.class, version, all.get(2));
        var seen = new java.util.ArrayList<>(shown);
        String cursor = (String) page1.get("next_cursor");
        while (cursor != null) {
            var next = questions.list(memberCaller, project, null, cursor, 2);
            items(next).forEach(i -> seen.add(i.get("id").toString()));
            cursor = (String) next.get("next_cursor");
        }
        assertThat(seen).doesNotHaveDuplicates().contains(String.valueOf(added)).hasSize(4);
    }

    @Test void listValidatesInputAndProjectAccess() {
        generator.runOnce();
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.list(memberCaller, project, null, null, 0));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.list(memberCaller, project, null, null, 101));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.list(memberCaller, project, null, "x", null));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.list(memberCaller, project, null, "1:2", null));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.list(memberCaller, project, null, "-3", null));
        var outsider = com.beez.backend.common.auth.CallerContext.human(fixtures().account());
        fails(HttpStatus.NOT_FOUND, "not_found", () -> questions.list(outsider, project, null, null, null));
    }

    @Test void legacyQuestionsWithoutGeneratedFieldsStillList() {
        jdbc.update("INSERT INTO planning_question(project_id,document_version_id,kind,question,origin) VALUES(?,?,'CLARIFICATION','예전 질문','human')", project, version);
        var item = items(questions.list(leaderCaller, project, version, null, null)).get(0);
        assertThat(item).containsEntry("question", "예전 질문").doesNotContainKey("question_type");
    }

    // ---- PLN-A04 답변 ----

    @Test void leaderAnswersEachTypeAndTheLastAnswerWins() {
        generator.runOnce();
        long[] id = ids();
        var essay = questions.answer(leaderCaller, project, id[0], new AnswerRequest("팀장만 삭제한다.", null, null));
        assertThat(essay).containsEntry("status", "ANSWERED").containsEntry("answer", "팀장만 삭제한다.").containsEntry("answered_by", "" + leader);
        assertThat(essay.get("answer_payload")).isEqualTo(Map.of("type", "ESSAY", "text", "팀장만 삭제한다."));
        assertThat(questions.answer(leaderCaller, project, id[1], new AnswerRequest("나", null, null)).get("answer_payload"))
                .isEqualTo(Map.of("type", "CHOICE", "selected", List.of("나")));
        assertThat(questions.answer(leaderCaller, project, id[1], new AnswerRequest(null, Map.of("type", "CHOICE", "selected", List.of("다"), "other_text", "직접"), null))
                .get("answer")).isEqualTo("다, 직접");
        // 같은 질문에 다시 보내면 덮어쓴다
        assertThat(questions.answer(leaderCaller, project, id[0], new AnswerRequest("수정한 답", null, null))).containsEntry("answer", "수정한 답");
        // 답변은 설계 항목을 만들거나 바꾸지 않는다
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=?", project)).isZero();
    }

    @Test void invalidAnswersAreRejectedWithoutSaving() {
        generator.runOnce();
        long[] id = ids();
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.answer(leaderCaller, project, id[0], new AnswerRequest("  ", null, null)));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.answer(leaderCaller, project, id[1], new AnswerRequest(null, Map.of("type", "CHOICE", "selected", List.of("라")), null)));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.answer(leaderCaller, project, id[2], new AnswerRequest("가".repeat(201), null, null)));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.answer(leaderCaller, project, id[0], new AnswerRequest("답", null, "OPEN")));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> questions.answer(leaderCaller, project, id[0], new AnswerRequest("답", null, "DISMISSED")));
        assertThat(count("SELECT count(*) FROM planning_question WHERE status='ANSWERED' AND project_id=?", project)).isZero();
    }

    @Test void dismissingClearsTheAnswer() {
        generator.runOnce();
        long a = ids()[0];
        questions.answer(leaderCaller, project, a, new AnswerRequest("답", null, null));
        var dismissed = questions.answer(leaderCaller, project, a, new AnswerRequest(null, null, "DISMISSED"));
        assertThat(dismissed).containsEntry("status", "DISMISSED").doesNotContainKeys("answer", "answered_by");
    }

    @Test void onlyTheLeaderAnswersAndOnlyInsideTheProject() {
        generator.runOnce();
        long a = ids()[0];
        fails(HttpStatus.FORBIDDEN, "forbidden", () -> questions.answer(memberCaller, project, a, new AnswerRequest("답", null, null)));
        var other = fixtures().projectWithLeader();
        fails(HttpStatus.NOT_FOUND, "not_found", () -> questions.answer(com.beez.backend.common.auth.CallerContext.human(other.leaderAccountId()), other.projectId(), a, new AnswerRequest("답", null, null)));
        assertThat(count("SELECT count(*) FROM planning_question WHERE status='ANSWERED' AND project_id IN (?,?)", project, other.projectId())).isZero();
    }

    @Test void answeringIsRefusedOnceTheInitialBaselineIsPublished() {
        generator.runOnce();
        long a = ids()[0];
        publishBaseline();
        fails(HttpStatus.CONFLICT, "design_already_published", () -> questions.answer(leaderCaller, project, a, new AnswerRequest("답", null, null)));
    }

    // ---- HTTP ----

    @Test void httpContractForListAndAnswer() throws Exception {
        generator.runOnce();
        var email = fixtures().emailOf(leader);
        ApiClient api = client().login(email, AccessFixtures.PASSWORD);
        var list = api.get("/api/projects/" + project + "/planning-questions?document_version_id=" + version + "&limit=2");
        assertThat(list.statusCode()).isEqualTo(200);
        var body = JSON.readTree(list.body());
        assertThat(body.get("total_count").asInt()).isEqualTo(3);
        assertThat(body.get("items").get(0).get("number").asInt()).isEqualTo(1);
        long id = body.get("items").get(0).get("id").asLong();

        String path = "/api/projects/" + project + "/planning-questions/" + id;
        assertThat(api.patch(path, "{\"answer\":\"답\"}").statusCode()).as("Origin 없는 변경 요청은 거절").isEqualTo(403);
        var ok = api.patch(path, "{\"answer\":\"답\"}", "Origin", ORIGIN);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(ok.body()).get("answer").asString()).isEqualTo("답");
    }
}
