package com.beez.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.planning.dto.AnswerRequest;
import com.beez.backend.planning.dto.DraftDtos.AdoptRequest;
import com.beez.backend.planning.dto.DraftDtos.CreateRequest;
import com.beez.backend.planning.service.DesignDraftRunner;
import com.beez.backend.planning.service.DesignDraftService;
import com.beez.backend.planning.service.PlanningQuestionGenerator;
import com.beez.backend.planning.service.PlanningQuestionService;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.json.JsonMapper;

/**
 * PLN-A05 설계 초안 생성: 접수 → 작업(AI 134) → 생성 후보 보존 → 팀장 채택. AI 는 {@link AiClient} 대역이다.
 * 핵심: 후보를 만드는 것만으로는 설계 항목이 하나도 바뀌지 않고(사람이 만든 초안이 덮이지 않는다), 채택할 때 당시와 지금이 같은지 다시 확인한다.
 */
class DesignDraftIntegrationTest extends PlanningTestSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> ITEM_TABLES = List.of("design_item", "design_item_version");

    @Autowired DesignDraftService drafts;
    @Autowired DesignDraftRunner runner;
    @Autowired PlanningQuestionGenerator generator;
    @Autowired PlanningQuestionService questions;
    @MockitoBean AiClient ai;

    long essayQuestion;
    long[] existing;       // {item, itemVersion}
    final AtomicReference<Map<String, Object>> response = new AtomicReference<>();

    @BeforeEach void setup() {
        doAnswer(call -> questionsResponse("a1", "a2")).when(ai).planningQuestions(any());
        generator.runOnce();
        essayQuestion = jdbc.queryForObject("SELECT id FROM planning_question WHERE question_key='q-a1' AND project_id=?", Long.class, project);
        questions.answer(leaderCaller, project, essayQuestion, new AnswerRequest("삭제는 팀장만 한다.", null, null));
        existing = draftItem("POL-001", "POLICY", "삭제는 누구나 한다.");
        response.set(draftResponse());
        doAnswer(call -> response.get()).when(ai).designDrafts(any());
    }

    // ---- 응답 도우미 (134 계약) ----

    Map<String, Object> source() {
        return Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1, "block_id", "blk_3",
                "locator", Map.of("type", "text_lines", "start_line", 3, "end_line", 3), "location", "기획서 / L3", "quote", "삭제 권한은 아직 정하지 않았다.");
    }

    /** UPDATE 후보 하나(기존 POL-001 갱신)와 CREATE 후보 하나. */
    Map<String, Object> draftResponse() {
        var update = new LinkedHashMap<String, Object>();
        update.put("candidate_key", "draft-update"); update.put("kind", "POLICY"); update.put("status", "DRAFT"); update.put("schema_version", 1);
        update.put("content", Map.of("description", "삭제는 팀장만 한다.")); update.put("base_design_item_version_id", "" + existing[1]);
        update.put("base_status", "DRAFT"); update.put("sources", List.of(source())); update.put("answer_refs", List.of("q-a1"));
        update.put("support", "DOCUMENT_AND_ANSWER"); update.put("rationale", "답변으로 확정");
        var create = new LinkedHashMap<String, Object>();
        create.put("candidate_key", "draft-create"); create.put("kind", "REQUIREMENT"); create.put("status", "DRAFT"); create.put("schema_version", 1);
        create.put("content", Map.of("description", "로그인은 이메일로 한다.")); create.put("sources", List.of(source())); create.put("answer_refs", List.of());
        create.put("support", "DOCUMENT");
        var changeUpdate = new LinkedHashMap<String, Object>();
        changeUpdate.put("candidate_key", "draft-update"); changeUpdate.put("change", "UPDATE"); changeUpdate.put("base_design_item_version_id", "" + existing[1]);
        changeUpdate.put("before", Map.of("description", "삭제는 누구나 한다.")); changeUpdate.put("after", Map.of("description", "삭제는 팀장만 한다."));
        changeUpdate.put("changed_fields", List.of(Map.of("field", "description", "change", "CHANGED", "before", "삭제는 누구나 한다.", "after", "삭제는 팀장만 한다.")));
        changeUpdate.put("conflicting_fields", List.of()); changeUpdate.put("answer_refs", List.of("q-a1"));
        var changeCreate = new LinkedHashMap<String, Object>();
        changeCreate.put("candidate_key", "draft-create"); changeCreate.put("change", "CREATE"); changeCreate.put("after", Map.of("description", "로그인은 이메일로 한다."));
        changeCreate.put("changed_fields", List.of()); changeCreate.put("conflicting_fields", List.of()); changeCreate.put("answer_refs", List.of());
        var result = new LinkedHashMap<String, Object>();
        result.put("document", Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1));
        result.put("kind", "PLAN"); result.put("text_hash", TEXT_HASH); result.put("parser_profile", "markdown-v1"); result.put("max_items", 50);
        result.put("items", new ArrayList<>(List.of(update, create))); result.put("changes", new ArrayList<>(List.of(changeUpdate, changeCreate)));
        result.put("untouched_existing_ids", List.of()); result.put("unanswered_question_keys", List.of("q-a2"));
        result.put("unresolved", List.of("삭제 보존 기간은 미정")); result.put("omitted_items", 0);
        return Map.of("result", result, "origin", "MODEL", "model", "test-model", "usage", List.of(usage("BEE_DRAFT")));
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> result() { return (Map<String, Object>) response.get().get("result"); }

    @SuppressWarnings("unchecked")
    Map<String, Object> item(String key) {
        return ((List<Map<String, Object>>) result().get("items")).stream().filter(i -> key.equals(i.get("candidate_key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> change(String key) {
        return ((List<Map<String, Object>>) result().get("changes")).stream().filter(i -> key.equals(i.get("candidate_key"))).findFirst().orElseThrow();
    }

    Map<String, Object> create(String key) { return drafts.create(leaderCaller, project, new CreateRequest(List.of("" + version)), key); }

    /** 접수하고 워커가 끝낸 뒤의 후보 id (candidate_key → id) */
    Map<String, Long> run(String key) {
        create(key);
        assertThat(runner.runOnce()).isTrue();
        var ids = new LinkedHashMap<String, Long>();
        jdbc.query("SELECT id,candidate_key FROM design_draft_candidate WHERE project_id=? ORDER BY id", rs -> { ids.put(rs.getString(2), rs.getLong(1)); }, project);
        return ids;
    }

    int itemRows() { return count("SELECT count(*) FROM design_item WHERE project_id=?", project) * 1000 + count("SELECT count(*) FROM design_item_version v JOIN design_item i ON i.id=v.design_item_id WHERE i.project_id=?", project); }

    // ---- 접수 ----

    @Test void acceptingStoresTheInputSnapshotAndCallsNoAi() {
        var accepted = create("k1");
        assertThat(accepted).containsEntry("status", "PENDING").containsEntry("document_version_ids", List.of("" + version));
        verifyNoAiDraftCall();
        var job = jdbc.queryForMap("SELECT status,input_snapshot::text AS snapshot FROM design_draft_job WHERE project_id=?", project);
        assertThat(job.get("status")).isEqualTo("PENDING");
        var snapshot = JSON.readTree(job.get("snapshot").toString());
        assertThat(snapshot.get("existing_items").get(0).get("key").asString()).isEqualTo("POL-001");
        assertThat(snapshot.get("existing_items").get(0).get("source").get("location").asString()).isEqualTo("L1-L2");
        var answered = snapshot.get("questions");
        assertThat(answered).hasSize(2);
        assertThat(answered.get(0).get("status").asString()).isEqualTo("ANSWERED");
        assertThat(answered.get(0).get("answer").get("answer").get("text").asString()).isEqualTo("삭제는 팀장만 한다.");
        assertThat(answered.get(1).has("answer")).isFalse();
    }

    void verifyNoAiDraftCall() { org.mockito.Mockito.verify(ai, org.mockito.Mockito.never()).designDrafts(any()); }

    @Test void sameKeyReplaysAndAnActiveJobBlocksANewRequest() {
        var first = create("k1");
        assertThat(create("k1")).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM design_draft_job WHERE project_id=?", project)).isEqualTo(1);
        fails(HttpStatus.CONFLICT, "draft_in_progress", () -> create("k2"));
        fails(HttpStatus.CONFLICT, "idempotency_conflict", () -> drafts.create(leaderCaller, project, new CreateRequest(List.of("" + version, "999999")), "k1"));
    }

    @Test void acceptRulesPermissionsAndAnalysisState() {
        fails(HttpStatus.FORBIDDEN, "forbidden", () -> drafts.create(memberCaller, project, new CreateRequest(List.of("" + version)), "m"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.create(leaderCaller, project, new CreateRequest(List.of()), "e"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.create(leaderCaller, project, new CreateRequest(List.of("" + version, "" + version)), "d"));
        fails(HttpStatus.NOT_FOUND, "not_found", () -> drafts.create(leaderCaller, project, new CreateRequest(List.of("987654")), "x"));
        jdbc.update("UPDATE document_processing SET status='FAILED',source_status='FAILED',index_status='FAILED',error_code='x' WHERE document_version_id=?", version);
        fails(HttpStatus.CONFLICT, "analysis_not_completed", () -> create("n"));
        jdbc.update("UPDATE document_processing SET status='COMPLETED',source_status='READY',index_status='READY',error_code=null WHERE document_version_id=?", version);
        publishBaseline();
        fails(HttpStatus.CONFLICT, "baseline_exists", () -> create("b"));
        assertThat(count("SELECT count(*) FROM design_draft_job WHERE project_id=?", project)).isZero();
    }

    // ---- 작업 → 후보 ----

    @Test void runnerStoresCandidatesWithoutTouchingAnyDesignItem() {
        int before = itemRows();
        var ids = run("k1");
        assertThat(ids.keySet()).containsExactly("draft-update", "draft-create");
        assertThat(itemRows()).as("후보만 만들고 설계 항목은 그대로").isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content::text FROM design_item_version WHERE id=?", String.class, existing[1])).contains("누구나");

        ArgumentCaptor<Map<String, Object>> sent = captor();
        verify(ai).designDrafts(sent.capture());
        assertThat(sent.getValue()).containsEntry("project_id", "" + project).containsEntry("text_hash", TEXT_HASH).containsEntry("content_hash", CONTENT_HASH).containsKey("request_key");
        // 저장·스냅샷은 sha256: 접두어 형태지만 AI 스키마는 접두어 없는 hex 64자만 받는다(S15P31A108-373).
        assertThat((String) sent.getValue().get("content_hash")).matches(HEX64);
        assertThat((String) sent.getValue().get("text_hash")).matches(HEX64);
        assertThat((List<?>) sent.getValue().get("existing_items")).hasSize(1);
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND NOT failed", project)).isEqualTo(1);

        var view = drafts.list(memberCaller, project, version);
        var job = ((List<Map<String, Object>>) view.get("jobs")).get(0);
        assertThat(job).containsEntry("status", "COMPLETED").containsEntry("unanswered_question_keys", List.of("q-a2"));
        var candidates = (List<Map<String, Object>>) job.get("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).containsEntry("change", "UPDATE").containsEntry("status", "PENDING").containsEntry("before", Map.of("description", "삭제는 누구나 한다."));
        assertThat(candidates.get(1)).containsEntry("change", "CREATE").doesNotContainKey("before");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    ArgumentCaptor<Map<String, Object>> captor() { return ArgumentCaptor.forClass((Class) Map.class); }

    @Test void candidatesAreImmutable() {
        run("k1");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("UPDATE design_draft_candidate SET proposed_after='{}'::jsonb WHERE project_id=?", project))
                .hasMessageContaining("변경할 수 없습니다");
    }

    @Test void aiFailureMarksTheJobFailedAndLeavesEverythingElse() {
        int before = itemRows();
        doThrow(new ResourceAccessException("down", new SocketTimeoutException("slow"))).when(ai).designDrafts(any());
        create("k1");
        runner.runOnce();
        assertThat(jdbc.queryForMap("SELECT status,error_code,retryable FROM design_draft_job WHERE project_id=?", project))
                .containsEntry("status", "FAILED").containsEntry("error_code", "ai_timeout").containsEntry("retryable", true);
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isZero();
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed", project)).isEqualTo(1);
        assertThat(itemRows()).isEqualTo(before);
        // 실패한 작업은 새 요청으로 다시 만든다
        doAnswer(call -> response.get()).when(ai).designDrafts(any());
        create("k2");
        runner.runOnce();
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isEqualTo(2);
    }

    @Test void outputThatContradictsTheSentInputIsRejectedWholesale() {
        change("draft-update").put("before", Map.of("description", "보내지 않은 본문"));
        create("k1");
        runner.runOnce();
        assertThat(jdbc.queryForMap("SELECT status,error_code FROM design_draft_job WHERE project_id=?", project))
                .containsEntry("status", "FAILED").containsEntry("error_code", "invalid_ai_output");
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isZero();

        response.set(draftResponse());
        item("draft-update").put("answer_refs", List.of("q-a2"));   // 답변되지 않은 질문을 근거로 댐
        create("k2");
        runner.runOnce();
        assertThat(jdbc.queryForObject("SELECT error_code FROM design_draft_job WHERE project_id=? ORDER BY id DESC LIMIT 1", String.class, project)).isEqualTo("invalid_ai_output");
    }

    @Test void baselinePublishedWhileTheAiRunsFailsTheJob() {
        doAnswer(call -> { publishBaseline(); return response.get(); }).when(ai).designDrafts(any());
        create("k1");
        runner.runOnce();
        assertThat(jdbc.queryForMap("SELECT status,error_code,retryable FROM design_draft_job WHERE project_id=?", project))
                .containsEntry("error_code", "baseline_exists").containsEntry("retryable", false);
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isZero();
    }

    // ---- 채택 ----

    @Test void adoptingCreateAndUpdateCandidatesWritesDraftsWithAiProvenance() {
        var ids = run("k1");
        var created = drafts.adopt(leaderCaller, project, ids.get("draft-create"), null);
        assertThat(created).containsEntry("status", "ADOPTED");
        var row = jdbc.queryForMap("""
                SELECT i.key,v.status,v.origin,v.title,v.source_document_version_id,v.source_locator::text AS locator FROM design_item_version v
                  JOIN design_item i ON i.id=v.design_item_id WHERE v.id=?
                """, Long.parseLong(created.get("design_item_version_id").toString()));
        assertThat(row).containsEntry("key", "REQ-001").containsEntry("status", "DRAFT").containsEntry("origin", "AI_PROPOSED")
                .containsEntry("title", "로그인은 이메일로 한다.").containsEntry("source_document_version_id", version);
        assertThat(row.get("locator").toString()).contains("start_line", "기획서 / L3");

        var updated = drafts.adopt(leaderCaller, project, ids.get("draft-update"), null);
        assertThat(updated).containsEntry("design_item_version_id", "" + existing[1]);
        assertThat(jdbc.queryForObject("SELECT content::text FROM design_item_version WHERE id=?", String.class, existing[1])).contains("팀장만");
        assertThat(jdbc.queryForObject("SELECT content_hash FROM design_item_version WHERE id=?", String.class, existing[1]))
                .isEqualTo(DesignJson.hash(Map.of("description", "삭제는 팀장만 한다.")));
    }

    @Test void adoptingTheSameCandidateAgainReturnsTheSameResultWithoutDuplicating() {
        var ids = run("k1");
        var first = drafts.adopt(leaderCaller, project, ids.get("draft-create"), null);
        var again = drafts.adopt(leaderCaller, project, ids.get("draft-create"), new AdoptRequest(true, List.of()));
        assertThat(again).isEqualTo(first);
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=? AND key LIKE 'REQ-%'", project)).isEqualTo(1);
    }

    @Test void aHumanEditAfterGenerationBlocksOnlyThatCandidate() {
        var ids = run("k1");
        // 후보를 만든 뒤 사람이 같은 항목을 고쳤다 (PLN-A08)
        String human = "{\"description\":\"삭제는 관리자만 한다.\"}";
        jdbc.update("UPDATE design_item_version SET content=cast(? AS jsonb),content_hash=? WHERE id=?", human,
                DesignJson.hash(Map.of("description", "삭제는 관리자만 한다.")), existing[1]);
        fails(HttpStatus.CONFLICT, "candidate_stale", () -> drafts.adopt(leaderCaller, project, ids.get("draft-update"), null));
        assertThat(jdbc.queryForObject("SELECT content::text FROM design_item_version WHERE id=?", String.class, existing[1])).contains("관리자만");
        assertThat(jdbc.queryForObject("SELECT status FROM design_draft_candidate WHERE id=?", String.class, ids.get("draft-update"))).isEqualTo("PENDING");
        // 다른 후보는 영향이 없다
        assertThat(drafts.adopt(leaderCaller, project, ids.get("draft-create"), null)).containsEntry("status", "ADOPTED");
    }

    @Test void regeneratingNeverOverwritesHumanDraftsAndStaleCandidatesCannotBeAdopted() {
        var first = run("k1");
        String human = "{\"description\":\"사람이 고친 문장\"}";
        jdbc.update("UPDATE design_item_version SET content=cast(? AS jsonb),content_hash=? WHERE id=?", human,
                DesignJson.hash(Map.of("description", "사람이 고친 문장")), existing[1]);
        // 다시 생성해도 사람이 고친 본문은 그대로 — AI 입력 스냅샷에는 고친 본문이 실린다
        change("draft-update").put("before", Map.of("description", "사람이 고친 문장"));
        change("draft-update").put("after", Map.of("description", "삭제는 팀장만 한다."));
        run("k2");
        assertThat(jdbc.queryForObject("SELECT content::text FROM design_item_version WHERE id=?", String.class, existing[1])).contains("사람이 고친");
        // 예전 후보(첫 생성)는 채택할 수 없고, 새 후보(고친 본문 기준)는 채택할 수 있다
        fails(HttpStatus.CONFLICT, "candidate_stale", () -> drafts.adopt(leaderCaller, project, first.get("draft-update"), null));
        long newer = jdbc.queryForObject("SELECT max(id) FROM design_draft_candidate WHERE candidate_key='draft-update' AND project_id=?", Long.class, project);
        assertThat(drafts.adopt(leaderCaller, project, newer, null)).containsEntry("status", "ADOPTED");
    }

    @Test void changedAnswerOrReanalyzedDocumentMakesCandidatesStale() {
        var ids = run("k1");
        questions.answer(leaderCaller, project, essayQuestion, new AnswerRequest("삭제는 아무도 못 한다.", null, null));
        fails(HttpStatus.CONFLICT, "candidate_stale", () -> drafts.adopt(leaderCaller, project, ids.get("draft-update"), null));
        assertThat(drafts.adopt(leaderCaller, project, ids.get("draft-create"), null)).as("답변을 쓰지 않은 후보는 영향 없음").containsEntry("status", "ADOPTED");

        var more = run("k2");
        jdbc.update("UPDATE document_processing SET text_hash=? WHERE document_version_id=?", "1".repeat(64), version);
        fails(HttpStatus.CONFLICT, "candidate_stale", () -> drafts.adopt(leaderCaller, project, more.get("draft-create"), null));
    }

    // S15P31A108-414: 질문 생성은 다른 문서·이전 버전에서 답한 결정을 다시 묻지 않는다. 그래서 초안에 그 답의 원문과 출처를 넘기고,
    // 그 답에 기댄 후보는 답이 바뀌면 채택할 수 없다.
    @SuppressWarnings("unchecked")
    @Test void answersFromOtherDocumentsReachTheDraftAndChangingThemMakesCitingCandidatesStale() {
        long other = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'API_SPEC','API 명세',?) RETURNING id", Long.class, project, leader);
        long otherVersion = documentVersion(other, 1, CONTENT);
        long related = jdbc.queryForObject("""
                INSERT INTO planning_question(project_id,document_version_id,kind,question,origin,status,answer,answer_payload,answered_by,answered_at)
                VALUES(?,?,'CLARIFICATION','이미 취소된 예약을 다시 취소하면?','model','ANSWERED','409로 거부한다.',cast(? AS jsonb),?,now()) RETURNING id
                """, Long.class, project, otherVersion, "{\"type\":\"ESSAY\",\"text\":\"409로 거부한다.\"}", leader);
        jdbc.update("INSERT INTO planning_question(project_id,document_version_id,kind,question,origin,status) VALUES(?,?,'CLARIFICATION','다른 문서 미답','model','OPEN')",
                project, otherVersion);
        String ref = "pq-" + related;
        item("draft-create").put("answer_refs", List.of(ref));
        item("draft-create").put("support", "DOCUMENT_AND_ANSWER");
        change("draft-create").put("answer_refs", List.of(ref));

        var ids = run("k1");
        ArgumentCaptor<Map<String, Object>> sent = captor();
        verify(ai).designDrafts(sent.capture());
        assertThat((List<Map<String, Object>>) sent.getValue().get("related_answers")).as("이 버전의 답·미답변은 빼고 다른 곳의 답만")
                .containsExactly(Map.of("ref_key", ref, "question", "이미 취소된 예약을 다시 취소하면?",
                        "answer", Map.of("type", "ESSAY", "text", "409로 거부한다."),
                        "source", Map.of("document_id", "" + other, "document_version_id", "" + otherVersion, "version_no", 1)));
        assertThat(jdbc.queryForObject("SELECT answer_refs::text FROM design_draft_candidate WHERE id=?", String.class, ids.get("draft-create"))).contains(ref);

        jdbc.update("UPDATE planning_question SET answer='별도 오류 응답',answer_payload=cast(? AS jsonb) WHERE id=?",
                "{\"type\":\"ESSAY\",\"text\":\"별도 오류 응답\"}", related);
        fails(HttpStatus.CONFLICT, "candidate_stale", () -> drafts.adopt(leaderCaller, project, ids.get("draft-create"), new AdoptRequest(true, List.of())));
        assertThat(drafts.adopt(leaderCaller, project, ids.get("draft-update"), null)).as("그 답을 쓰지 않은 후보는 영향 없음").containsEntry("status", "ADOPTED");
    }

    // 이 필드가 생기기 전에 접수된 작업(스냅샷에 related_answers 없음)도 그대로 실행·채택된다.
    @Test void jobsAcceptedBeforeRelatedAnswersStillRunAndAdopt() {
        create("k1");
        jdbc.update("UPDATE design_draft_job SET input_snapshot=input_snapshot - 'related_answers' WHERE project_id=?", project);
        assertThat(runner.runOnce()).isTrue();
        long update = jdbc.queryForObject("SELECT id FROM design_draft_candidate WHERE project_id=? AND candidate_key='draft-update'", Long.class, project);
        assertThat(drafts.adopt(leaderCaller, project, update, null)).containsEntry("status", "ADOPTED");
    }

    @Test void evidenceAndConflictConfirmationsAreRequired() {
        item("draft-create").put("support", "NONE");
        change("draft-update").put("conflicting_fields", List.of("description"));
        var ids = run("k1");
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.adopt(leaderCaller, project, ids.get("draft-create"), null));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.adopt(leaderCaller, project, ids.get("draft-create"), new AdoptRequest(false, null)));
        assertThat(drafts.adopt(leaderCaller, project, ids.get("draft-create"), new AdoptRequest(true, null))).containsEntry("status", "ADOPTED");

        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.adopt(leaderCaller, project, ids.get("draft-update"), null));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.adopt(leaderCaller, project, ids.get("draft-update"), new AdoptRequest(null, List.of("other"))));
        assertThat(drafts.adopt(leaderCaller, project, ids.get("draft-update"), new AdoptRequest(null, List.of("description")))).containsEntry("status", "ADOPTED");
    }

    @Test void discardKeepsDesignItemsAndBlocksLaterAdoption() {
        var ids = run("k1");
        int before = itemRows();
        assertThat(drafts.discard(leaderCaller, project, ids.get("draft-create"))).containsEntry("status", "DISCARDED");
        assertThat(drafts.discard(leaderCaller, project, ids.get("draft-create"))).containsEntry("status", "DISCARDED");
        assertThat(itemRows()).isEqualTo(before);
        fails(HttpStatus.CONFLICT, "candidate_discarded", () -> drafts.adopt(leaderCaller, project, ids.get("draft-create"), null));
        drafts.adopt(leaderCaller, project, ids.get("draft-update"), null);
        fails(HttpStatus.CONFLICT, "candidate_adopted", () -> drafts.discard(leaderCaller, project, ids.get("draft-update")));
    }

    @Test void adoptionIsLeaderOnlyProjectScopedAndBlockedAfterBaseline() {
        var ids = run("k1");
        fails(HttpStatus.FORBIDDEN, "forbidden", () -> drafts.adopt(memberCaller, project, ids.get("draft-create"), null));
        var other = fixtures().projectWithLeader();
        fails(HttpStatus.NOT_FOUND, "not_found", () -> drafts.adopt(CallerContext.human(other.leaderAccountId()), other.projectId(), ids.get("draft-create"), null));
        publishBaseline();
        fails(HttpStatus.CONFLICT, "baseline_exists", () -> drafts.adopt(leaderCaller, project, ids.get("draft-create"), null));
    }

    // ---- HTTP ----

    @Test void httpContract() throws Exception {
        ApiClient api = client().login(fixtures().emailOf(leader), AccessFixtures.PASSWORD);
        String base = "/api/projects/" + project;
        String body = "{\"document_version_ids\":[\"" + version + "\"]}";
        assertThat(api.post(base + "/design-drafts", body, "Idempotency-Key", "h1").statusCode()).as("Origin 필요").isEqualTo(403);
        var accepted = api.post(base + "/design-drafts", body, "Origin", ORIGIN, "Idempotency-Key", "h1");
        assertThat(accepted.statusCode()).isEqualTo(202);
        assertThat(JSON.readTree(accepted.body()).get("status").asString()).isEqualTo("PENDING");
        runner.runOnce();
        var list = JSON.readTree(api.get(base + "/design-drafts?document_version_id=" + version).body());
        assertThat(list.get("jobs").get(0).get("candidates")).hasSize(2);
        long create = list.get("jobs").get(0).get("candidates").get(1).get("id").asLong();
        var adopted = api.post(base + "/design-draft-candidates/" + create + "/adopt", "{}", "Origin", ORIGIN);
        assertThat(adopted.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(adopted.body()).get("status").asString()).isEqualTo("ADOPTED");
    }
}
