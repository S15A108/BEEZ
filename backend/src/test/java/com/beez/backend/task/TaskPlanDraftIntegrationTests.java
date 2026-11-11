package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.document.service.DocumentIndexWorker;
import com.beez.backend.document.service.DocumentIndexWorker.ChunkEvidence;
import com.beez.backend.document.service.DocumentIndexWorker.Retrieval;
import com.beez.backend.project.ProjectRole;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.ApiClient;
import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 작업 계획 문서 초안(S15P31A108-385): 실제 HTTP·Postgres 와 AI·검색 대역으로 권한, 검증, 실패 분류, 사용량, 저장 없음을 확인한다. */
class TaskPlanDraftIntegrationTests extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PLAN_TEXT = "고객은 앱에서 메뉴를 고르고 결제하면 주문 번호를 받는다.";
    @Autowired JdbcTemplate db;
    @MockitoBean AiClient ai;
    @MockitoBean DocumentIndexWorker documents;
    private AccessFixtures.ProjectWithLeader project;
    private ApiClient leader;
    private long baselineId;
    private long itemId;
    private long versionId;
    private long documentId;
    private long documentVersionId;

    @BeforeEach void setUp() {
        project = fixtures().projectWithLeader();
        leader = client().login(project.leaderEmail(), AccessFixtures.PASSWORD);
        long pid = project.projectId();
        itemId = db.queryForObject("insert into design_item(project_id, key, kind) values (?, 'API-04', 'API_CONTRACT') returning id", Long.class, pid);
        versionId = db.queryForObject("""
                insert into design_item_version(design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                values (?, 1, 'APPROVED', 'POST /orders', 1, '{"description":"주문 생성","method":"POST","path":"/orders"}'::jsonb, 'h-api04', 'HUMAN', now())
                returning id""", Long.class, itemId);
        long reqId = db.queryForObject("insert into design_item(project_id, key, kind) values (?, 'REQ-12', 'REQUIREMENT') returning id", Long.class, pid);
        long reqVersion = db.queryForObject("""
                insert into design_item_version(design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                values (?, 1, 'APPROVED', '주문 번호 형식', 1, '{"description":"주문 번호는 6자리 숫자로 발급한다"}'::jsonb, 'h-req12', 'HUMAN', now())
                returning id""", Long.class, reqId);
        baselineId = db.queryForObject("insert into baseline(project_id, version_no, published_by) values (?, 1, ?) returning id", Long.class,
                pid, project.leaderAccountId());
        db.update("insert into baseline_item(baseline_id, project_id, design_item_id, design_item_version_id) values (?, ?, ?, ?)", baselineId, pid, itemId, versionId);
        db.update("insert into baseline_item(baseline_id, project_id, design_item_id, design_item_version_id) values (?, ?, ?, ?)", baselineId, pid, reqId, reqVersion);
        db.update("update project set current_baseline_id = ? where id = ?", baselineId, pid);
        documentId = db.queryForObject("insert into document(project_id, type, title, created_by) values (?, 'REQ_SPEC', '요구사항 명세서', ?) returning id",
                Long.class, pid, project.leaderAccountId());
        documentVersionId = db.queryForObject("""
                insert into document_version(document_id, version_no, status, storage_key, content_hash, content_text, approved_at)
                values (?, 2, 'APPROVED', 'k', 'h', ?, now()) returning id""", Long.class, documentId, PLAN_TEXT);
        when(documents.retrieveEvidence(eq(pid), anyString(), anyInt())).thenReturn(retrieval(List.of(chunk(1, PLAN_TEXT))));
    }

    private Retrieval retrieval(List<ChunkEvidence> evidence) {
        return new Retrieval(baselineId, List.of(documentVersionId), evidence);
    }

    private ChunkEvidence chunk(long id, String text) {
        return new ChunkEvidence(id, documentId, documentVersionId, 2, "chunk-" + id, text, Map.of("start_line", 12, "end_line", 14), "요구사항 명세서");
    }

    private String path() { return "/api/projects/" + project.projectId() + "/task-plan-drafts"; }

    private HttpResponse<String> draft(ApiClient caller, String body) {
        return caller.post(path(), body);
    }

    private static final String BODY = "{\"title\":\"주문 생성 API 만들기\",\"memo\":\"결제 연동은 나중에\"}";

    private Map<String, Object> aiReply(Object... overrides) {
        Map<String, Object> result = new java.util.LinkedHashMap<>(Map.of(
                "status", "DRAFTED",
                "plan", Map.of("goal", "주문 API를 만든다", "included_scope", List.of("주문 생성"), "excluded_scope", List.of("환불"),
                        "approach", "저장 후 번호 발급", "completion_criteria", List.of("번호가 응답된다")),
                "evidence", List.of(Map.of("field", "goal", "document_id", String.valueOf(documentId), "document_type", "REQUIREMENTS",
                        "document_version_id", String.valueOf(documentVersionId), "version_no", 2, "location", "요구사항 명세서 · 12~14행",
                        "quote", "메뉴를 고르고 결제하면 주문 번호를 받는다")),
                "unsupported_fields", List.of("approach"),
                "design_ref_candidates", List.of(
                        Map.of("design_item_id", String.valueOf(itemId), "reason", "주문 생성 API라서"),
                        Map.of("design_item_id", "999999", "reason", "없는 항목")),
                "new_design_item_candidates", List.of(),
                "unlinked", List.of(Map.of("text", "주문 번호 형식"))));
        for (int i = 0; i + 1 < overrides.length; i += 2) result.put((String) overrides[i], overrides[i + 1]);
        return Map.of("origin", "MODEL", "model", "fixture", "result", result, "usage", List.of(Map.of(
                "request_key", "llm-task-plan-task-plan-draft-" + project.leaderAccountId() + "-x-1", "feature", "BEE_DRAFT", "model", "fixture",
                "latency_ms", 9000, "failed", false, "tokens_known", true, "input_tokens", 5200, "output_tokens", 640)));
    }

    private long taskCount() { return db.queryForObject("select count(*) from task where project_id = ?", Long.class, project.projectId()); }

    @Test void fillsFromApprovedDocumentsKeepsOnlyApprovedItemsAndStoresNothing() {
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        long before = taskCount();
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("DRAFTED");
        assertThat(body.path("plan").path("goal").asString()).isEqualTo("주문 API를 만든다");
        assertThat(body.path("evidence").get(0).path("location").asString()).contains("12~14행");
        assertThat(body.path("design_ref_candidates").size()).isEqualTo(1);
        JsonNode link = body.path("design_ref_candidates").get(0);
        assertThat(link.path("design_item_id").asString()).isEqualTo(String.valueOf(itemId));
        assertThat(link.path("design_item_version_id").asString()).isEqualTo(String.valueOf(versionId));
        assertThat(link.path("key").asString()).isEqualTo("API-04");
        assertThat(body.path("unlinked").get(0).path("text").asString()).isEqualTo("주문 번호 형식");
        assertThat(body.path("baseline_id").asString()).isEqualTo(String.valueOf(baselineId));
        assertThat(taskCount()).isEqualTo(before);
        assertThat(db.queryForObject("select count(*) from design_item where project_id = ?", Long.class, project.projectId())).isEqualTo(2L);
    }

    @Test void sendsApprovedExcerptsAndDesignItemsToTheModel() {
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        draft(leader, BODY);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(ai).taskPlanDrafts(sent.capture());
        Map<String, Object> input = sent.getValue();
        assertThat(input).containsEntry("project_id", String.valueOf(project.projectId()));
        assertThat((String) input.get("request_key")).startsWith("task-plan-draft-" + project.leaderAccountId() + "-");
        @SuppressWarnings("unchecked") var excerpt = ((List<Map<String, Object>>) input.get("excerpts")).get(0);
        assertThat(excerpt).containsEntry("document_type", "REQUIREMENTS").containsEntry("version_no", 2).containsEntry("text", PLAN_TEXT);
        assertThat((String) excerpt.get("location")).isEqualTo("요구사항 명세서 · 12~14행");
        assertThat((List<?>) input.get("design_items")).hasSize(2);
        assertThat(((Map<?, ?>) input.get("task")).get("memo")).isEqualTo("결제 연동은 나중에");
    }

    @Test void overlongExcerptsAreSkippedNotCut() {
        String huge = "가".repeat(TaskPlanDraftServiceLimits.MAX_EXCERPT_CHARS + 1);
        when(documents.retrieveEvidence(eq(project.projectId()), anyString(), anyInt()))
                .thenReturn(retrieval(List.of(chunk(1, huge), chunk(2, PLAN_TEXT))));
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        draft(leader, BODY);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(ai).taskPlanDrafts(sent.capture());
        @SuppressWarnings("unchecked") var excerpts = (List<Map<String, Object>>) sent.getValue().get("excerpts");
        assertThat(excerpts).hasSize(1);
        assertThat(excerpts.get(0).get("excerpt_id")).isEqualTo("c2");
    }

    @Test void newItemWithInvalidContentIsDroppedAndDuplicateBecomesALink() {
        var source = List.of(Map.of("document_id", String.valueOf(documentId), "document_version_id", String.valueOf(documentVersionId),
                "version_no", 2, "quote", "주문 번호를 받는다", "document_type", "REQUIREMENTS", "location", "요구사항 명세서 · 12~14행"));
        var invalid = Map.of("kind", "API_CONTRACT", "content", Map.of("description", "경로 없음"), "rationale", "r", "evidence", List.of());
        var duplicate = Map.of("kind", "REQUIREMENT", "content", Map.of("description", "주문 번호는 6자리 숫자로 발급한다"), "rationale", "r", "evidence", source);
        var fresh = Map.of("kind", "POLICY", "content", Map.of("description", "주문은 10분 안에 취소할 수 있다"), "rationale", "문서에 있음", "evidence", source);
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply("design_ref_candidates", List.of(), "new_design_item_candidates", List.of(invalid, duplicate, fresh)));
        var body = JSON.readTree(draft(leader, BODY).body());
        assertThat(body.path("new_design_item_candidates").size()).isEqualTo(1);
        assertThat(body.path("new_design_item_candidates").get(0).path("kind").asString()).isEqualTo("POLICY");
        assertThat(body.path("design_ref_candidates").size()).isEqualTo(1);
        assertThat(body.path("design_ref_candidates").get(0).path("key").asString()).isEqualTo("REQ-12");
    }

    @Test void recordsTheCallUsage() {
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        draft(leader, BODY);
        var row = db.queryForMap("select feature, input_tokens, output_tokens, latency_ms, failed from llm_usage where project_id = ? and request_key like ?",
                project.projectId(), "%task-plan-draft-" + project.leaderAccountId() + "-%");
        assertThat(row).containsEntry("feature", "BEE_DRAFT").containsEntry("input_tokens", 5200).containsEntry("output_tokens", 640)
                .containsEntry("latency_ms", 9000).containsEntry("failed", false);
    }

    @Test void excludesUnrelatedDesignButChecksDuplicatesAgainstTheWholeApprovedBaseline() {
        var source = List.of(Map.of("document_id", String.valueOf(documentId), "document_version_id", String.valueOf(documentVersionId),
                "version_no", 2, "quote", "주문 번호를 받는다", "document_type", "REQUIREMENTS", "location", "문서"));
        var duplicate = Map.of("kind", "REQUIREMENT", "content", Map.of("description", "주문 번호는 6자리 숫자로 발급한다"),
                "rationale", "r", "evidence", source);
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply("design_ref_candidates", List.of(), "new_design_item_candidates", List.of(duplicate)));
        var response = draft(leader, "{\"title\":\"로그인\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(ai).taskPlanDrafts(sent.capture());
        assertThat((List<?>) sent.getValue().get("design_items")).isEmpty();
        var result = JSON.readTree(response.body());
        assertThat(result.path("new_design_item_candidates").size()).isZero();
        assertThat(result.path("design_ref_candidates").get(0).path("key").asString()).isEqualTo("REQ-12");
    }

    @Test void expandsOnlyConfirmedExplicitOneHopDependencies() {
        long reqId = db.queryForObject("select id from design_item where project_id=? and key='REQ-12'", Long.class, project.projectId());
        db.update("""
                insert into design_item_dependency(project_id,from_item_id,to_item_id,origin,status,confirmed_by,confirmed_at)
                values(?,?,?,'EXPLICIT','CONFIRMED',?,now())
                """, project.projectId(), itemId, reqId, project.leaderAccountId());
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        assertThat(draft(leader, "{\"title\":\"/orders\"}").statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(ai).taskPlanDrafts(sent.capture());
        assertThat((List<?>) sent.getValue().get("design_items")).hasSize(2);
        db.update("update design_item_dependency set status='NEEDS_RECHECK' where from_item_id=?", itemId);
        clearInvocations(ai);
        assertThat(draft(leader, "{\"title\":\"/orders\"}").statusCode()).isEqualTo(200);
        verify(ai).taskPlanDrafts(sent.capture());
        assertThat((List<?>) sent.getValue().get("design_items")).hasSize(1);
    }

    /** 어휘로 고른 항목이 한도(100)를 채워도 확정 의존 항목이 들어갈 자리가 남는다. */
    @Test void keepsRoomForDependenciesWhenTheLexicalSelectionFillsTheLimit() {
        long pid = project.projectId();
        long firstBulk = 0;
        for (int i = 0; i < 105; i++) {
            long id = db.queryForObject("insert into design_item(project_id, key, kind) values (?, ?, 'REQUIREMENT') returning id", Long.class, pid, "BULK-" + i);
            long version = db.queryForObject("""
                    insert into design_item_version(design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                    values (?, 1, 'APPROVED', ?, 1, '{"description":"대량 항목"}'::jsonb, ?, 'HUMAN', now()) returning id""",
                    Long.class, id, "벌크항목 " + i, "h-bulk" + i);
            db.update("insert into baseline_item(baseline_id, project_id, design_item_id, design_item_version_id) values (?, ?, ?, ?)", baselineId, pid, id, version);
            if (i == 0) firstBulk = id;
        }
        long reqId = db.queryForObject("select id from design_item where project_id=? and key='REQ-12'", Long.class, pid);
        db.update("""
                insert into design_item_dependency(project_id,from_item_id,to_item_id,origin,status,confirmed_by,confirmed_at)
                values(?,?,?,'EXPLICIT','CONFIRMED',?,now())
                """, pid, firstBulk, reqId, project.leaderAccountId());
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        assertThat(draft(leader, "{\"title\":\"벌크항목\"}").statusCode()).isEqualTo(200);
        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(ai).taskPlanDrafts(sent.capture());
        @SuppressWarnings("unchecked") var sentItems = (List<Map<String, Object>>) sent.getValue().get("design_items");
        assertThat(sentItems.size()).isLessThanOrEqualTo(100);
        assertThat(sentItems).extracting(item -> item.get("key")).contains("BULK-0", "REQ-12");
    }

    @Test void rejectsQuotesCreatedByJoiningNonadjacentTableRows() {
        String original = "| 기능 | 설명 |\n| --- | --- |\n| 로그인 | 게스트 |\n| AI | 탐색 |\n| 로그인 | 소셜 |\n";
        when(documents.retrieveEvidence(eq(project.projectId()), anyString(), anyInt()))
                .thenReturn(retrieval(List.of(chunk(1, original))));
        var fabricated = Map.of("field", "goal", "document_id", String.valueOf(documentId),
                "document_version_id", String.valueOf(documentVersionId), "version_no", 2,
                "quote", "| 로그인 | 게스트 |\n| 로그인 | 소셜 |");
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply("evidence", List.of(fabricated)));
        var response = draft(leader, "{\"title\":\"로그인\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        var result = JSON.readTree(response.body());
        assertThat(result.path("evidence").size()).isZero();
        assertThat(result.path("unsupported_fields").toString()).contains("goal");
    }

    @Test void aTimeoutIsAiTimeoutAndCountsAFailedCall() {
        // RestClient 는 504 를 HttpServerErrorException.create 로 만든다(GatewayTimeout 하위 클래스).
        when(ai.taskPlanDrafts(anyMap())).thenThrow(HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "t", new HttpHeaders(),
                "{\"error\":{\"code\":\"model_timeout\"}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("ai_timeout");
        assertThat(db.queryForObject("select count(*) from llm_usage where project_id = ? and failed", Long.class, project.projectId())).isEqualTo(1L);
        assertThat(taskCount()).isZero();
    }

    @Test void anOutputCutByTheCapIsAiOutputTooLongWithTheRealUsage() {
        String error = "{\"error\":{\"code\":\"model_output_truncated\"},\"usage\":[{\"request_key\":\"llm-task-plan-task-plan-draft-"
                + project.leaderAccountId() + "-x-2\",\"feature\":\"BEE_DRAFT\",\"model\":\"fixture\",\"latency_ms\":12000,\"failed\":true,"
                + "\"tokens_known\":true,\"input_tokens\":4800,\"output_tokens\":1500}]}";
        when(ai.taskPlanDrafts(anyMap())).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "b", new HttpHeaders(),
                error.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("ai_output_too_long");
        var row = db.queryForMap("select input_tokens, output_tokens, failed from llm_usage where project_id = ?", project.projectId());
        assertThat(row).containsEntry("input_tokens", 4800).containsEntry("output_tokens", 1500).containsEntry("failed", true);
    }

    @Test void anUnreachableAiIsAiUnavailable() {
        when(ai.taskPlanDrafts(anyMap())).thenThrow(new ResourceAccessException("connection refused"));
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("ai_unavailable");
    }

    @Test void noApprovedDocumentContentIsInsufficientDocumentsAndNeverCallsTheModel() {
        when(documents.retrieveEvidence(eq(project.projectId()), anyString(), anyInt())).thenReturn(retrieval(List.of()));
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("insufficient_documents");
        verify(ai, never()).taskPlanDrafts(anyMap());
    }

    @Test void withoutABaselineItIsBaselineMissing() {
        db.update("update project set current_baseline_id = null where id = ?", project.projectId());
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("baseline_missing");
        verify(ai, never()).taskPlanDrafts(anyMap());
    }

    @Test void accessRules() {
        long outsider = fixtures().account();
        var other = client().login(fixtures().emailOf(outsider), AccessFixtures.PASSWORD);
        assertThat(draft(other, BODY).statusCode()).isEqualTo(404);
        assertThat(draft(client(), BODY).statusCode()).isEqualTo(401);
        long member = fixtures().account();
        fixtures().member(project.projectId(), member, ProjectRole.MEMBER);
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        assertThat(draft(client().login(fixtures().emailOf(member), AccessFixtures.PASSWORD), BODY).statusCode()).isEqualTo(200);
        verifyNoMoreInteractions(ignoreStubs(documents));
    }

    @Test void validatesTheRequest() {
        assertThat(draft(leader, "{\"title\":\"  \"}").statusCode()).isEqualTo(400);
        assertThat(draft(leader, "{\"memo\":\"x\"}").statusCode()).isEqualTo(400);
        verify(ai, never()).taskPlanDrafts(anyMap());
    }

    @Test void theHourlyLimitStopsTheCallBeforeAnyWork() {
        for (int i = 0; i < 20; i++) {
            db.update("""
                    insert into llm_usage(project_id, feature, request_key, provider, model, tokens_known, latency_ms, failed, origin)
                    values (?, 'BEE_DRAFT', ?, 'p', 'm', false, 1, false, 'model')""",
                    project.projectId(), "llm-task-plan-task-plan-draft-" + project.leaderAccountId() + "-old" + i + ":0");
        }
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("draft_limit_exceeded");
        verify(ai, never()).taskPlanDrafts(anyMap());
        verify(documents, never()).retrieveEvidence(anyLong(), anyString(), anyInt());
    }

    private String beKey(String suffix) {
        return "task-plan-draft-" + project.leaderAccountId() + "-" + suffix;
    }

    private void usageRow(String requestKey) {
        db.update("""
                insert into llm_usage(project_id, feature, request_key, provider, model, tokens_known, latency_ms, failed, origin)
                values (?, 'BEE_DRAFT', ?, 'p', 'm', false, 1, false, 'model')""", project.projectId(), requestKey);
    }

    /** 한 번 누른 것이 사용량 줄을 여러 개 남겨도 한 번으로 센다. */
    @Test void severalUsageRowsOfOneClickCountOnce() {
        // 19번의 호출이 각각 두 줄씩(성공 줄과 실패 보충 줄). 줄 수로 세면 38줄이라 막히지만 호출 수로는 19번이다.
        for (int i = 0; i < 19; i++) {
            String uuid = java.util.UUID.randomUUID().toString();
            usageRow("llm-task-plan-" + beKey(uuid) + "-abcdef012345");
            usageRow(beKey(uuid) + ":failed");
        }
        when(ai.taskPlanDrafts(anyMap())).thenReturn(aiReply());
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        // 이번 호출까지 20번이 되었으므로 다음 호출은 막힌다.
        var next = draft(leader, BODY);
        assertThat(next.statusCode()).isEqualTo(429);
    }

    /** 모델을 부르기 전에 거절돼도 검색어 임베딩 비용이 나가므로 시간당 횟수에 센다. */
    @Test void requestsRejectedBeforeTheModelCountTowardTheLimit() {
        when(documents.retrieveEvidence(eq(project.projectId()), anyString(), anyInt())).thenReturn(retrieval(List.of()));
        for (int i = 0; i < 20; i++) {
            assertThat(draft(leader, BODY).statusCode()).as("call " + i).isEqualTo(409);
        }
        var response = draft(leader, BODY);
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(JSON.readTree(response.body()).path("error").path("code").asString()).isEqualTo("draft_limit_exceeded");
        verify(ai, never()).taskPlanDrafts(anyMap());
    }

    /** 서비스의 상수를 시험에서 같은 값으로 쓴다. */
    private static final class TaskPlanDraftServiceLimits {
        static final int MAX_EXCERPT_CHARS = 4000;
    }
}
