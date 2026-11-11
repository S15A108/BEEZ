package com.beez.backend.planning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beez.backend.ai.AiClient;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.planning.dto.DraftDtos.BatchItem;
import com.beez.backend.planning.dto.DraftDtos.BatchRequest;
import com.beez.backend.planning.dto.DraftDtos.CreateRequest;
import com.beez.backend.planning.repository.DesignDraftRepository;
import com.beez.backend.planning.service.AutoDraftIntake;
import com.beez.backend.planning.service.DesignDraftAdoptionService;
import com.beez.backend.planning.service.DesignDraftRunner;
import com.beez.backend.planning.service.DesignDraftService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.client.HttpServerErrorException;

/**
 * S15P31A108-389 승인 문서만으로 설계 항목 후보를 자동 생성한다: 자동 접수 조건과 중복 접수, 부분 나누기와 부분별 호출,
 * 부분 실패 뒤 실패한 부분만 재시도, 시도 상한, 후보 겹침 표시, 후보 일괄 채택(건별 결과). AI 는 {@link AiClient} 대역이다.
 */
@TestPropertySource(properties = {"beez.planning.part-max-chars=500", "beez.planning.part-max-items=5"})
class DocumentOnlyDraftIntegrationTest extends PlanningTestSupport {
    @Autowired DesignDraftService drafts;
    @Autowired DesignDraftRunner runner;
    @Autowired AutoDraftIntake intake;
    @Autowired DesignDraftRepository repository;
    @Autowired DesignDraftAdoptionService batches;
    @MockitoBean AiClient ai;
    @MockitoSpyBean BaselineRepository baselines;

    /** 절 6개짜리 긴 문서 — 부분 크기 500자라 여러 부분으로 나뉜다. */
    static final String LONG = IntStream.rangeClosed(1, 6).mapToObj(i -> "## 절 " + i + "\n\n"
            + IntStream.range(0, 12).mapToObj(j -> "절 " + i + "의 규칙 " + j + "은 이렇게 동작해야 한다.").collect(Collectors.joining("\n\n")) + "\n\n")
            .collect(Collectors.joining("# 긴 문서\n\n", "# 긴 문서\n\n", ""));

    final AtomicInteger calls = new AtomicInteger();
    final List<Map<String, Object>> sent = new ArrayList<>();
    /** 부분 번호(1부터)가 여기에 있고 아직 한 번도 실패시키지 않았으면 출력 상한으로 끊긴다. */
    final java.util.Set<Integer> truncateOnce = new java.util.HashSet<>();
    boolean alwaysTimeout;
    long longVersion;

    @BeforeEach void setup() {
        // 긴 문서를 가진 승인본 하나를 기준으로 쓴다(기존 fixture 의 짧은 문서는 그대로 둔다).
        long doc = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id", Long.class, project, leader);
        longVersion = documentVersion(doc, 1, LONG);
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_by=created_by,approved_at=now() WHERE id=?", longVersion);
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_by=created_by,approved_at=now() WHERE id=?", version);
        doAnswer(call -> {
            Map<String, Object> input = call.getArgument(0);
            sent.add(input);
            calls.incrementAndGet();
            if (alwaysTimeout) throw HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "x", HttpHeaders.EMPTY, new byte[0], null);
            @SuppressWarnings("unchecked") var scope = (Map<String, Object>) input.get("scope");
            int start = ((Number) scope.get("start_line")).intValue();
            if (truncateOnce.remove(partOf(input))) {
                throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", HttpHeaders.EMPTY,
                        "{\"error\":{\"code\":\"model_output_truncated\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            return response(input, "절 시작 " + start + " 규칙은 이렇게 동작해야 한다.", "REQUIREMENT", "DOCUMENT");
        }).when(ai).designDrafts(any());
    }

    int partOf(Map<String, Object> input) {
        @SuppressWarnings("unchecked") var scope = (Map<String, Object>) input.get("scope");
        int start = ((Number) scope.get("start_line")).intValue();
        var parts = jdbc.queryForList("SELECT part_no,start_line FROM design_draft_job_part WHERE job_id IN (SELECT id FROM design_draft_job WHERE document_version_id=?) ORDER BY part_no",
                Long.parseLong(((Map<?, ?>) input.get("document")).get("document_version_id").toString()));
        return parts.stream().filter(p -> ((Number) p.get("start_line")).intValue() == start).map(p -> ((Number) p.get("part_no")).intValue()).findFirst().orElse(-1);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> response(Map<String, Object> input, String description, String kind, String support) {
        var doc = (Map<String, Object>) input.get("document");
        String key = "draft-" + Math.abs(description.hashCode());
        var item = new LinkedHashMap<String, Object>();
        item.put("candidate_key", key); item.put("kind", kind); item.put("status", "DRAFT"); item.put("schema_version", 1);
        item.put("content", Map.of("description", description)); item.put("sources", List.of()); item.put("answer_refs", List.of());
        item.put("support", support); item.put("rationale", "문서에 있음");
        if ("DOCUMENT".equals(support)) item.put("sources", List.of(Map.of("document_id", doc.get("document_id"), "document_version_id", doc.get("document_version_id"),
                "version_no", 1, "block_id", "blk_1", "locator", Map.of("type", "text_lines", "start_line", 1, "end_line", 1), "location", "문서 / L1", "quote", "인용")));
        var change = new LinkedHashMap<String, Object>();
        change.put("candidate_key", key); change.put("change", "CREATE"); change.put("after", Map.of("description", description));
        change.put("changed_fields", List.of()); change.put("conflicting_fields", List.of()); change.put("answer_refs", List.of());
        var result = new LinkedHashMap<String, Object>();
        result.put("document", doc); result.put("kind", input.get("kind")); result.put("text_hash", input.get("text_hash")); result.put("parser_profile", "markdown-v1");
        result.put("max_items", 5); result.put("items", new ArrayList<>(List.of(item))); result.put("changes", new ArrayList<>(List.of(change)));
        result.put("untouched_existing_ids", List.of()); result.put("unanswered_question_keys", List.of()); result.put("unresolved", List.of()); result.put("omitted_items", 0);
        return Map.of("result", result, "origin", "MODEL", "model", "test-model", "usage", List.of(usage("BEE_DRAFT")));
    }

    /** 이 프로젝트의 승인본 4종(PLAN=기존 fixture, REQ_SPEC=긴 문서, ERD, API_SPEC)을 갖춘다. */
    void fourApproved() {
        for (String type : List.of("ERD", "API_SPEC")) {
            long doc = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,?,?,?) RETURNING id", Long.class, project, type, type, leader);
            long v = documentVersion(doc, 1, "# " + type + "\n\n본문 하나.\n");
            jdbc.update("UPDATE document_version SET status='APPROVED',approved_by=created_by,approved_at=now() WHERE id=?", v);
        }
        doReturn(true).when(baselines).isIndexReady(anyLong(), anyString());
    }

    int jobs(String where, Object... args) { return count("SELECT count(*) FROM design_draft_job WHERE project_id=? " + where, prepend(args)); }

    Object[] prepend(Object[] args) {
        var all = new Object[args.length + 1];
        all[0] = project;
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    Map<String, Object> manual(String key, long... versions) {
        return drafts.create(leaderCaller, project, new CreateRequest(java.util.Arrays.stream(versions).mapToObj(String::valueOf).toList(), "DOCUMENT_ONLY"), key);
    }

    // ---- 자동 접수 ----

    @Test void autoIntakeSubmitsOneJobPerDocumentVersionOnceAllFourAreApprovedAndIndexed() {
        fourApproved();
        assertThat(intake.runOnce()).isGreaterThanOrEqualTo(4);
        assertThat(jobs("AND trigger_kind='AUTO' AND mode='DOCUMENT_ONLY' AND status='PENDING'")).isEqualTo(4);
        assertThat(count("SELECT count(*) FROM design_draft_job_part p JOIN design_draft_job j ON j.id=p.job_id WHERE j.project_id=? AND j.document_version_id=?", project, longVersion))
                .isGreaterThan(1);
        verify(ai, times(0)).designDrafts(any());                       // 접수는 AI 를 부르지 않는다
        int again = intake.runOnce();                                     // 두 번째 틱은 이 프로젝트에 새로 접수하지 않는다
        assertThat(jobs("")).isEqualTo(4);
        assertThat(again).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("SELECT requested_by FROM design_draft_job WHERE project_id=? LIMIT 1", Long.class, project)).isEqualTo(leader);
    }

    @Test void autoIntakeWaitsUntilAllFourTypesAreApproved() {
        doReturn(true).when(baselines).isIndexReady(anyLong(), anyString());
        intake.runOnce();                                                 // PLAN, REQ_SPEC 둘뿐
        assertThat(jobs("")).isZero();
    }

    @Test void autoIntakeWaitsForTheIndexOfEveryDocument() {
        fourApproved();
        doAnswer(call -> (long) call.getArgument(0) != longVersion).when(baselines).isIndexReady(anyLong(), anyString());
        intake.runOnce();
        assertThat(jobs("")).isZero();
    }

    @Test void autoIntakeSkipsProjectsWithABaseline() {
        fourApproved();
        publishBaseline();
        intake.runOnce();
        assertThat(jobs("")).isZero();
    }

    // ---- 부분별 호출과 합치기 ----

    @Test void runnerCallsTheAiOncePerPartWithItsLineRangeAndStoresTheCandidates() {
        manual("m1", longVersion);
        int parts = count("SELECT count(*) FROM design_draft_job_part p JOIN design_draft_job j ON j.id=p.job_id WHERE j.document_version_id=?", longVersion);
        assertThat(parts).isGreaterThan(1);
        assertThat(runner.runOnce()).isTrue();
        assertThat(calls.get()).isEqualTo(parts);
        assertThat(sent).allSatisfy(input -> {
            assertThat(input).containsEntry("mode", "DOCUMENT_ONLY").containsEntry("max_items", 5);
            assertThat((List<?>) input.get("questions")).isEmpty();
            assertThat((List<?>) input.get("existing_items")).isEmpty();
        });
        assertThat(sent.stream().map(i -> ((Map<?, ?>) i.get("scope")).get("start_line")).distinct().count()).isEqualTo(parts);
        assertThat(jdbc.queryForObject("SELECT status FROM design_draft_job WHERE document_version_id=?", String.class, longVersion)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isEqualTo(parts);
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT'", project)).isEqualTo(parts);
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=?", project)).isZero();     // 후보만 생기고 설계 항목은 없다
    }

    // ---- 부분 실패와 재시도 ----

    @Test void aFailedPartKeepsTheOtherCandidatesAndRetryRerunsOnlyThatPart() {
        manual("m1", longVersion);
        int parts = count("SELECT count(*) FROM design_draft_job_part p JOIN design_draft_job j ON j.id=p.job_id WHERE j.document_version_id=?", longVersion);
        truncateOnce.add(2);
        runner.runOnce();
        assertThat(calls.get()).isEqualTo(parts);                         // 출력 상한은 그 부분만 실패시키고 나머지는 계속한다
        var job = jdbc.queryForMap("SELECT status,error_code,attempt_no FROM design_draft_job WHERE document_version_id=?", longVersion);
        assertThat(job).containsEntry("status", "FAILED").containsEntry("error_code", "ai_output_too_long").containsEntry("attempt_no", 1);
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isEqualTo(parts - 1);
        var view = ((List<?>) drafts.list(leaderCaller, project, longVersion).get("jobs")).get(0);
        assertThat(((Map<?, ?>) view).get("candidates")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(parts - 1);
        assertThat(((Map<?, ?>) ((Map<?, ?>) view).get("parts")).get("failed")).isEqualTo(1L);

        int before = calls.get();
        manual("m2", longVersion);                                        // 다시 시도: 실패한 부분만
        assertThat(jdbc.queryForObject("SELECT status FROM design_draft_job WHERE document_version_id=?", String.class, longVersion)).isEqualTo("PENDING");
        runner.runOnce();
        assertThat(calls.get() - before).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT status,attempt_no FROM design_draft_job WHERE document_version_id=?", longVersion)).containsEntry("status", "COMPLETED").containsEntry("attempt_no", 2);
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=?", project)).isEqualTo(parts);
        fails(HttpStatus.CONFLICT, "draft_already_generated", () -> manual("m3", longVersion));
    }

    @Test void aTimeoutStopsTheRemainingPartsAndRetryResumesThem() {
        manual("m1", longVersion);
        int parts = count("SELECT count(*) FROM design_draft_job_part p JOIN design_draft_job j ON j.id=p.job_id WHERE j.document_version_id=?", longVersion);
        alwaysTimeout = true;
        runner.runOnce();
        assertThat(calls.get()).isEqualTo(1);                             // 첫 부분이 시간 초과면 나머지를 부르지 않는다
        assertThat(jdbc.queryForMap("SELECT status,error_code FROM design_draft_job WHERE document_version_id=?", longVersion))
                .containsEntry("status", "FAILED").containsEntry("error_code", "ai_timeout");
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND failed", project)).isEqualTo(1);
        alwaysTimeout = false;
        manual("m2", longVersion);
        runner.runOnce();
        assertThat(calls.get()).isEqualTo(1 + parts);
        assertThat(jdbc.queryForObject("SELECT status FROM design_draft_job WHERE document_version_id=?", String.class, longVersion)).isEqualTo("COMPLETED");
    }

    @Test void retriesStopAtTheAttemptLimit() {
        manual("m1", longVersion);
        alwaysTimeout = true;
        runner.runOnce();
        for (int attempt = 2; attempt <= 3; attempt++) {
            manual("m" + attempt, longVersion);
            runner.runOnce();
        }
        assertThat(jdbc.queryForObject("SELECT attempt_no FROM design_draft_job WHERE document_version_id=?", Integer.class, longVersion)).isEqualTo(3);
        fails(HttpStatus.CONFLICT, "draft_retry_limit_exceeded", () -> manual("m4", longVersion));
    }

    @Test void aRetryWhileTheJobIsRunningOrPendingIsRefused() {
        manual("m1", longVersion);
        fails(HttpStatus.CONFLICT, "draft_in_progress", () -> manual("m2", longVersion));
    }

    @Test void onlyTheLeaderCanRequestADocumentOnlyDraft() {
        fails(HttpStatus.FORBIDDEN, "forbidden", () -> drafts.create(memberCaller, project, new CreateRequest(List.of("" + longVersion), "DOCUMENT_ONLY"), "x"));
    }

    // ---- 겹침 표시 ----

    @Test void aCandidateLikeAnExistingItemIsStoredButMarked() {
        draftItem("REQ-001", "REQUIREMENT", "절 시작 1 규칙은 이렇게 동작해야 한다.");
        manual("m1", longVersion);
        runner.runOnce();
        var overlap = jdbc.queryForList("SELECT overlap_kind,overlap_ref FROM design_draft_candidate WHERE project_id=? AND overlap_kind IS NOT NULL", project);
        assertThat(overlap).hasSize(1);
        assertThat(overlap.get(0)).containsEntry("overlap_kind", "EXISTING_ITEM");
        var candidates = jdbc.queryForList("SELECT count(*) AS c FROM design_draft_candidate WHERE project_id=?", project);
        assertThat(((Number) candidates.get(0).get("c")).intValue()).isGreaterThan(1);
    }

    @Test void aDiscardedCandidateIsMarkedWhenTheSameOneComesAgain() {
        manual("m1", longVersion);
        runner.runOnce();
        long first = jdbc.queryForObject("SELECT id FROM design_draft_candidate WHERE project_id=? ORDER BY id LIMIT 1", Long.class, project);
        drafts.discard(leaderCaller, project, first);
        // 같은 내용을 새 문서 버전(요구사항 v2)에서 다시 제안한다.
        long doc = jdbc.queryForObject("SELECT document_id FROM document_version WHERE id=?", Long.class, longVersion);
        long v2 = documentVersion(doc, 2, LONG);
        jdbc.update("UPDATE document_version SET status='APPROVED',approved_by=created_by,approved_at=now() WHERE id=?", v2);
        manual("m2", v2);
        runner.runOnce();
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=? AND overlap_kind='DISCARDED_CANDIDATE' AND overlap_ref=?", project, first)).isGreaterThanOrEqualTo(1);
    }

    // ---- 리뷰 반영 (S15P31A108-389) ----

    @Test void aCandidateLikeAnotherDocumentsUndecidedCandidateIsMarkedAndAdoptingBothMakesOneItem() {
        // 두 문서가 같은 내용의 후보를 하나씩 만든다(첫 부분이 둘 다 같은 설명으로 나온다).
        manual("m1", longVersion, version);
        while (runner.runOnce()) { /* 접수된 작업을 모두 처리한다 */ }
        String description = "절 시작 1 규칙은 이렇게 동작해야 한다.";
        var same = jdbc.queryForList("SELECT id,overlap_kind,overlap_ref FROM design_draft_candidate WHERE project_id=? AND proposed_after->>'description'=? ORDER BY id", project, description);
        assertThat(same).hasSize(2);
        assertThat(same.get(0).get("overlap_kind")).isNull();                                   // 먼저 저장된 쪽은 새 후보
        assertThat(same.get(1)).containsEntry("overlap_kind", "PENDING_CANDIDATE");             // 뒤 문서의 같은 후보는 겹침으로 표시
        assertThat(((Number) same.get(1).get("overlap_ref")).longValue()).isEqualTo(((Number) same.get(0).get("id")).longValue());

        // 겹침으로 표시된 쪽까지 일괄로 채택해도 같은 설계 항목은 하나만 생긴다.
        var result = batches.adopt(leaderCaller, project, new BatchRequest(List.of(
                new BatchItem(same.get(0).get("id").toString(), null, null), new BatchItem(same.get(1).get("id").toString(), null, null))));
        @SuppressWarnings("unchecked") var results = (List<Map<String, Object>>) result.get("results");
        assertThat(results.get(0)).containsEntry("result", "ADOPTED");
        assertThat(results.get(1)).containsEntry("result", "FAILED").containsEntry("error_code", "candidate_duplicate");
        assertThat(count("SELECT count(*) FROM design_item_version v JOIN design_item i ON i.id=v.design_item_id WHERE i.project_id=? AND v.content->>'description'=?", project, description)).isEqualTo(1);
    }

    @Test void aSecondAdoptionOfTheSameContentIsRefusedEvenWhenNothingMarkedIt() {
        manual("m1", longVersion, version);
        while (runner.runOnce()) { /* 모두 처리한다 */ }
        String description = "절 시작 1 규칙은 이렇게 동작해야 한다.";
        var ids = jdbc.queryForList("SELECT id FROM design_draft_candidate WHERE project_id=? AND proposed_after->>'description'=? ORDER BY id", Long.class, project, description);
        // 겹침 표시가 없었던 것처럼 되돌려(다른 인스턴스가 동시에 저장한 경우) 채택 직전 확인만으로 막히는지 본다.
        jdbc.update("ALTER TABLE design_draft_candidate DISABLE TRIGGER trg_design_draft_candidate_immutable");
        jdbc.update("UPDATE design_draft_candidate SET overlap_kind=NULL,overlap_ref=NULL WHERE id=?", ids.get(1));
        jdbc.update("ALTER TABLE design_draft_candidate ENABLE TRIGGER trg_design_draft_candidate_immutable");
        drafts.adopt(leaderCaller, project, ids.get(0), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> drafts.adopt(leaderCaller, project, ids.get(1), null))
                .hasMessageContaining("같은 설계 항목");
    }

    @Test void autoIntakeDoesNotAddAJobToAVersionTheLeaderAlreadyCreatedByHand() {
        fourApproved();
        manual("m1", longVersion);
        intake.runOnce();
        assertThat(jobs("AND document_version_id=? AND mode='DOCUMENT_ONLY'", longVersion)).isEqualTo(1);
        assertThat(jobs("AND document_version_id=? AND trigger_kind='AUTO'", longVersion)).isZero();
        assertThat(jobs("AND trigger_kind='AUTO'")).isEqualTo(3);          // 손으로 만들지 않은 나머지 문서만 자동 접수
    }

    @Test void autoTargetsSkipProjectsApprovedBeforeTheCutoffAndAreReadFromTheCursorOn() {
        fourApproved();
        var now = java.time.Instant.now();
        assertThat(repository.autoTargetProjects(0, 10, null)).contains(project);
        assertThat(repository.autoTargetProjects(0, 10, now.minusSeconds(3600))).contains(project);
        assertThat(repository.autoTargetProjects(0, 10, now.plusSeconds(3600))).doesNotContain(project);   // 켜기 전에 이미 갖춰진 프로젝트는 소급하지 않는다
        assertThat(repository.autoTargetProjects(project, 10, null)).doesNotContain(project);              // 커서 뒤부터 본다
    }

    @Test void documentOnlyIntakeDoesNotReadExistingItemsSoManyOfThemDoNotBlockIt() {
        for (int i = 1; i <= 501; i++) draftItem(String.format("REQ-%03d", i), "REQUIREMENT", "이미 있는 항목 " + i);
        manual("m1", longVersion);                                                  // 기존 항목이 500개를 넘어도 접수된다
        assertThat(jobs("AND document_version_id=? AND mode='DOCUMENT_ONLY'", longVersion)).isEqualTo(1);
    }

    // ---- 일괄 채택 ----

    @Test void batchAdoptReportsEachCandidateAndAdoptsTheRestWhenSomeFail() {
        manual("m1", longVersion);
        runner.runOnce();
        var ids = jdbc.queryForList("SELECT id FROM design_draft_candidate WHERE project_id=? ORDER BY id", Long.class, project);
        assertThat(ids.size()).isGreaterThanOrEqualTo(4);
        long noEvidence = ids.get(1), stale = ids.get(2);
        jdbc.update("ALTER TABLE design_draft_candidate DISABLE TRIGGER trg_design_draft_candidate_immutable");
        jdbc.update("UPDATE design_draft_candidate SET support='NONE' WHERE id=?", noEvidence);
        jdbc.update("ALTER TABLE design_draft_candidate ENABLE TRIGGER trg_design_draft_candidate_immutable");
        jdbc.update("UPDATE document_processing SET text_hash=? WHERE document_version_id=? ", STORED_PREFIX + "0".repeat(64), longVersion);   // 원문이 다시 분석됐다 → 모두 stale

        var request = new BatchRequest(List.of(new BatchItem("" + ids.get(0), null, null), new BatchItem("" + noEvidence, null, null), new BatchItem("" + stale, null, null)));
        var result = batches.adopt(leaderCaller, project, request);
        @SuppressWarnings("unchecked") var results = (List<Map<String, Object>>) result.get("results");
        assertThat(results).hasSize(3);
        assertThat(results.stream().map(r -> r.get("result")).distinct().toList()).containsExactly("FAILED");
        assertThat(results.get(0)).containsEntry("error_code", "candidate_stale");
        assertThat(results.get(1)).containsEntry("error_code", "validation_failed");
        assertThat(result).containsEntry("succeeded", 0).containsEntry("failed", 3);
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=?", project)).isZero();
    }

    @Test void batchAdoptCreatesDraftItemsOnlyForTheChosenCandidates() {
        manual("m1", longVersion);
        runner.runOnce();
        var ids = jdbc.queryForList("SELECT id FROM design_draft_candidate WHERE project_id=? ORDER BY id", Long.class, project);
        var chosen = ids.subList(0, 2);
        var result = batches.adopt(leaderCaller, project, new BatchRequest(chosen.stream().map(id -> new BatchItem("" + id, null, null)).toList()));
        assertThat(result).containsEntry("succeeded", 2).containsEntry("failed", 0);
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=?", project)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM design_item_version v JOIN design_item i ON i.id=v.design_item_id WHERE i.project_id=? AND v.status='DRAFT' AND v.origin='AI_PROPOSED' AND v.source_document_version_id=?", project, longVersion)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=? AND status='PENDING'", project)).isEqualTo(ids.size() - 2);
        // 같은 요청을 다시 보내도 같은 결과(이미 채택한 후보는 새 항목을 더 만들지 않는다)
        batches.adopt(leaderCaller, project, new BatchRequest(chosen.stream().map(id -> new BatchItem("" + id, null, null)).toList()));
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=?", project)).isEqualTo(2);
    }

    @Test void batchDiscardMarksTheChosenCandidatesAndMakesNoItems() {
        manual("m1", longVersion);
        runner.runOnce();
        var ids = jdbc.queryForList("SELECT id FROM design_draft_candidate WHERE project_id=? ORDER BY id", Long.class, project);
        var result = batches.discard(leaderCaller, project, new BatchRequest(List.of(new BatchItem("" + ids.get(0), null, null))));
        assertThat(result).containsEntry("succeeded", 1);
        assertThat(count("SELECT count(*) FROM design_draft_candidate WHERE project_id=? AND status='DISCARDED'", project)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM design_item WHERE project_id=?", project)).isZero();
    }

    @Test void batchRequestsAreValidatedAndLeaderOnly() {
        fails(HttpStatus.FORBIDDEN, "forbidden", () -> batches.adopt(memberCaller, project, new BatchRequest(List.of(new BatchItem("1", null, null)))));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> batches.adopt(leaderCaller, project, new BatchRequest(List.of())));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> batches.adopt(leaderCaller, project, new BatchRequest(List.of(new BatchItem("1", null, null), new BatchItem("1", null, null)))));
        var tooMany = IntStream.rangeClosed(1, 51).mapToObj(i -> new BatchItem("" + i, null, null)).toList();
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> batches.adopt(leaderCaller, project, new BatchRequest(tooMany)));
    }

    @Test void aCandidateOfAnotherProjectIsNotFoundAndDoesNotStopTheRest() {
        manual("m1", longVersion);
        runner.runOnce();
        long mine = jdbc.queryForObject("SELECT id FROM design_draft_candidate WHERE project_id=? ORDER BY id LIMIT 1", Long.class, project);
        var result = batches.adopt(leaderCaller, project, new BatchRequest(List.of(new BatchItem("987654321", null, null), new BatchItem("" + mine, null, null))));
        @SuppressWarnings("unchecked") var results = (List<Map<String, Object>>) result.get("results");
        assertThat(results.get(0)).containsEntry("result", "FAILED").containsEntry("error_code", "not_found");
        assertThat(results.get(1)).containsEntry("result", "ADOPTED");
    }
}
