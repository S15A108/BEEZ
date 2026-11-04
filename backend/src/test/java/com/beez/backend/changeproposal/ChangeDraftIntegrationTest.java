package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.DraftDtos.Draft;
import com.beez.backend.changeproposal.dto.DraftDtos.Request;
import com.beez.backend.changeproposal.service.ChangeDraftService;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.AccessFixtures;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.*;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * S15P31A108-233 CHG-A07 문서 수정 초안 생성. AI(AI-A06)는 {@link AiClient} 대역이고 어댑터·서비스·저장소는 실제다.
 * 핵심: 읽기·생성 전용이라 문서·기준·제안이 하나도 바뀌지 않고, 늘어나는 것은 사용량과 멱등 기록뿐이다.
 */
class ChangeDraftIntegrationTest extends IntegrationTestSupport {
    static final String ORIGIN = "http://localhost:5173";
    static final List<String> STATE_TABLES = List.of("document", "document_version", "baseline", "baseline_document", "baseline_item",
            "design_item", "design_item_version", "change_proposal", "change_proposal_revision", "approval");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ChangeDraftService drafts;
    @MockitoBean AiClient ai;
    TaskApiSupport api;
    long project, leaderId, memberId, baseline, document, version;
    CallerContext member;

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource); api.resetAndLoadFixture();
        project = api.projectId(); leaderId = api.accountId("leader@test"); memberId = api.accountId("member@test");
        member = CallerContext.human(memberId);
        baseline = jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project);
        document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','요구사항',?) RETURNING id",
                Long.class, project, leaderId);
        version = approvedVersion(document, 1, "# 로그인\n이메일로 로그인한다.\n오류를 돌려준다.\n");
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, document, version);
        item("REQ-001");
        jdbc.update("DELETE FROM llm_usage WHERE project_id=?", project);
        // 실제 AI 는 우리가 준 request_key 로 호출마다 새 사용량 key 를 만든다. 대역도 호출마다 새로 만든다
        doAnswer(call -> reply("# 로그인\n이메일로 로그인한다.\n오류는 error.code로 돌려준다.\n")).when(ai).beeDraft(any());
    }

    // ---- 도우미 ----
    long approvedVersion(long doc, int no, String content) {
        return jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,?,'APPROVED','fixture.md','hash-'||?,?,?,?,now()) RETURNING id
                """, Long.class, doc, no, no, content, leaderId, leaderId);
    }
    /** 현재 기준에 들어 있고 출처가 {@link #version} 인 승인 항목 */
    long item(String key) {
        long item = jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,'REQUIREMENT') RETURNING id", Long.class, project, key);
        long itemVersion = jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,
                                                source_document_version_id,source_locator,approved_at)
                VALUES(?,1,'APPROVED',?,1,'{"description":"로그인한다"}','hash-'||?,'HUMAN',?,'{"type":"text_lines","start_line":1,"end_line":2}',now())
                RETURNING id
                """, Long.class, item, key, key, version);
        jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)", baseline, project, item, itemVersion);
        return item;
    }
    Map<String, Object> reply(String after) { return reply(after, List.of(source(version))); }
    Map<String, Object> reply(String after, List<Map<String, Object>> sources) {
        return Map.of("result", Map.of("after_content", after, "reason", "합의된 오류 처리 기준을 반영했다.", "sources", sources, "unresolved", List.of()),
                "origin", "MODEL", "model", "test-model",
                "usage", List.of(Map.of("request_key", "llm-draft-" + UUID.randomUUID(), "feature", "BEE_DRAFT", "model", "test-model",
                        "input_tokens", 100, "output_tokens", 50, "latency_ms", 1200, "failed", false, "tokens_known", true,
                        "estimated_cost_amount", "0.0042", "currency", "USD")));
    }
    Map<String, Object> source(long documentVersion) {
        return Map.of("document_id", "" + document, "document_version_id", "" + documentVersion, "version_no", 1, "location", "L1-L2");
    }
    Request request(String instruction) { return new Request("" + document, "" + version, instruction, null); }
    Draft generate(String key) { return drafts.generate(member, project, request("오류 응답 설명을 보완해 주세요."), key); }
    void fails(HttpStatus status, String code, ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status); assertThat(e.getCode()).isEqualTo(code);
        });
    }
    Map<String, Integer> counts() {
        var counts = new LinkedHashMap<String, Integer>();
        for (String table : STATE_TABLES) counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        return counts;
    }
    int usageRows() { return jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT'", Integer.class, project); }
    int failedUsageRows() {
        return jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='BEE_DRAFT' AND failed", Integer.class, project);
    }
    int savedCommands() { return jdbc.queryForObject("SELECT count(*) FROM design_http_command WHERE project_id=?", Integer.class, project); }
    /** 호출 중에 다른 제안이 발행된 것처럼 새 기준을 만든다. */
    void publishNewBaseline(boolean keepVersion) { newBaseline(keepVersion, "same"); }
    /**
     * 새 기준(v2)을 만든다. 다른 항목은 그대로 옮기고, 문서 승인본과 REQ-001 항목만 요청한 대로 둔다.
     * @param itemMode same(항목 그대로) / newVersion(REQ-001 이 새 버전) / retired(REQ-001 퇴역, 기준에서 빠짐)
     */
    void newBaseline(boolean keepVersion, String itemMode) {
        long next = keepVersion ? version : 0;
        if (!keepVersion) {
            jdbc.update("UPDATE document_version SET status='SUPERSEDED' WHERE id=?", version);
            next = approvedVersion(document, 2, "# 로그인\n다른 제안이 바꾼 본문\n");
        }
        long b2 = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id", Long.class, project, leaderId);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", b2, project, document, next);
        long req = jdbc.queryForObject("SELECT id FROM design_item WHERE project_id=? AND key='REQ-001'", Long.class, project);
        jdbc.update("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) "
                + "SELECT ?,project_id,design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=? AND design_item_id<>?", b2, baseline, req);
        switch (itemMode) {
            case "same" -> jdbc.update("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) "
                    + "SELECT ?,project_id,design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=? AND design_item_id=?", b2, baseline, req);
            case "newVersion" -> {
                long newer = jdbc.queryForObject("""
                        INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,
                                                        source_document_version_id,source_locator,approved_at)
                        VALUES(?,2,'APPROVED','REQ-001 v2',1,'{"description":"바뀐 항목"}','hash-v2','HUMAN',?,'{"type":"text_lines","start_line":1,"end_line":2}',now())
                        RETURNING id
                        """, Long.class, req, version);
                jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)", b2, project, req, newer);
            }
            case "retired" -> jdbc.update("UPDATE design_item SET retired_at=now() WHERE id=?", req);
            default -> throw new IllegalArgumentException(itemMode);
        }
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", b2, project);
    }

    // ---- 정상 경로와 상태 불변 (D1, D2) ----
    @Test void generatesAnUnapprovedDraftAndChangesNothingButUsage() {
        String original = jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?", String.class, version);
        var before = counts();
        Draft draft = generate("k-1");

        assertThat(draft.documentId()).isEqualTo("" + document);
        assertThat(draft.baseDocumentVersionId()).isEqualTo("" + version);
        assertThat(draft.afterContent()).contains("error.code");
        assertThat(draft.generation().origin()).isEqualTo("MODEL");
        assertThat(draft.generation().model()).isEqualTo("test-model");
        assertThat(draft.generation().text()).contains("합의된");
        assertThat(draft.generation().sources()).hasSize(1);
        assertThat(draft.generation().usage()).hasSize(1);
        // 문서·기준·제안은 하나도 바뀌지 않는다
        assertThat(counts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?", String.class, version)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project)).isEqualTo(baseline);
        // 늘어난 것은 사용량 한 줄과 멱등 기록 한 줄
        assertThat(usageRows()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT task_id,model,input_tokens,output_tokens,estimated_cost_amount,currency,failed,origin FROM llm_usage WHERE project_id=?", project);
        assertThat(row).containsEntry("model", "test-model").containsEntry("input_tokens", 100).containsEntry("currency", "USD")
                .containsEntry("failed", false).containsEntry("origin", "model");
        assertThat(row.get("task_id")).isNull();
        assertThat(savedCommands()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    @Test void sendsTheApprovedDocumentAndRelatedItemsToTheAiAndNotTheTask() {
        long task = createTask();
        drafts.generate(member, project, new Request("" + document, "" + version, "  오류 응답 설명을 보완해 주세요.  ", "" + task), "k-payload");
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai).beeDraft(captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertThat(sent).containsEntry("project_id", "" + project).containsEntry("instruction", "오류 응답 설명을 보완해 주세요.");
        assertThat((String) sent.get("request_key")).startsWith("chg-a07:" + project + ":" + memberId + ":");
        assertThat(sent).doesNotContainKey("task_id").doesNotContainKey("decisions");
        var doc = (Map<String, Object>) sent.get("document_version");
        assertThat(doc).containsEntry("id", "" + version).containsEntry("status", "APPROVED").containsEntry("file_name", "document-" + document + "-v1.md")
                .containsEntry("media_type", "text/markdown").containsEntry("is_current_baseline", true);
        assertThat((String) doc.get("content")).contains("이메일로 로그인한다");
        var items = (List<Map<String, Object>>) sent.get("approved_items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("key", "REQ-001").containsEntry("kind", "REQUIREMENT").containsEntry("status", "APPROVED");
        assertThat((Map<String, Object>) items.getFirst().get("content")).containsEntry("description", "로그인한다");
        assertThat((Map<String, Object>) items.getFirst().get("source")).containsEntry("document_version_id", "" + version).containsEntry("location", "L1-L2");
        // 작업은 AI 에 보내지 않고 사용량 귀속에만 쓴다
        assertThat(jdbc.queryForObject("SELECT task_id FROM llm_usage WHERE project_id=?", Long.class, project)).isEqualTo(task);
    }

    long createTask() {
        var res = api.post("/api/projects/" + project + "/tasks", api.planJson("초안 관련 작업", api.readsRef("API-02"), "[]"), memberId);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return TaskApiSupport.longField(res.body(), "id");
    }

    // ---- 권한·입력 검증 (D3, D4) ----
    @Test void httpShapeAndAccessRules() {
        String path = "/api/projects/" + project + "/change-drafts";
        String body = "{\"document_id\":\"" + document + "\",\"base_document_version_id\":\"" + version + "\",\"instruction\":\"오류 응답을 보완해 주세요.\"}";
        var ok = api.postWithHeaders(path, body, memberId, "Origin", ORIGIN, "Idempotency-Key", "http-ok");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
        assertThat(ok.body()).contains("\"document_id\":\"" + document + "\"", "\"base_document_version_id\":\"" + version + "\"",
                "\"after_content\":", "\"generation\":{\"origin\":\"MODEL\"", "\"unresolved\":[]");
        // 로그인하지 않으면 401, 프로젝트 멤버가 아니면 404
        assertThat(client().post(path, body, "Origin", ORIGIN, "Idempotency-Key", "http-anon").statusCode()).isEqualTo(401);
        long outsider = fixtures().account();
        var other = client().login(fixtures().emailOf(outsider), AccessFixtures.PASSWORD);
        assertThat(other.post(path, body, "Origin", ORIGIN, "Idempotency-Key", "http-out").statusCode()).isEqualTo(404);
        // 필수 값이 빠지면 400
        var bad = api.postWithHeaders(path, "{\"document_id\":\"" + document + "\"}", memberId, "Origin", ORIGIN, "Idempotency-Key", "http-bad");
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    @Test void unknownDocumentOrTaskIsNotFound() {
        fails(HttpStatus.NOT_FOUND, "not_found", () -> drafts.generate(member, project, new Request("999999", "" + version, "보완", null), "k-nodoc"));
        fails(HttpStatus.NOT_FOUND, "not_found", () -> drafts.generate(member, project, new Request("" + document, "" + version, "보완", "999999"), "k-notask"));
        verify(ai, never()).beeDraft(any());
    }

    @Test void invalidInputIsRejectedBeforeAnythingIsCalled() {
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, request("  "), "k-blank"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, request("가".repeat(2001)), "k-long"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, new Request("abc", "" + version, "보완", null), "k-id"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, new Request("" + document, null, "보완", null), "k-id2"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, new Request("" + document, "" + version, "보완", "0"), "k-task"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, null, "k-null"));
        fails(HttpStatus.BAD_REQUEST, "validation_failed", () -> drafts.generate(member, project, request("보완"), " "));
        verify(ai, never()).beeDraft(any());
        assertThat(usageRows()).isZero();
    }

    // ---- 기준 확인 (D5, D6) ----
    @Test void onlyTheCurrentApprovedVersionOfADocumentInTheBaselineIsAccepted() {
        long draftVersion = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by)
                VALUES(?,2,'DRAFT','x.md','hash-draft','초안',?) RETURNING id
                """, Long.class, document, leaderId);
        fails(HttpStatus.CONFLICT, "version_conflict", () -> drafts.generate(member, project, new Request("" + document, "" + draftVersion, "보완", null), "k-draft"));
        // 기준에 들어 있지 않은 문서
        long loose = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','기획',?) RETURNING id", Long.class, project, leaderId);
        long looseVersion = approvedVersion(loose, 1, "기획");
        fails(HttpStatus.CONFLICT, "version_conflict", () -> drafts.generate(member, project, new Request("" + loose, "" + looseVersion, "보완", null), "k-loose"));
        verify(ai, never()).beeDraft(any());
    }

    @Test void noBaselineYetIsReportedAsBaselineMissing() {
        var fresh = fixtures().projectWithLeader();
        long doc = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','기획',?) RETURNING id", Long.class, fresh.projectId(), fresh.leaderAccountId());
        long ver = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','x.md','h','기획',?,?,now()) RETURNING id
                """, Long.class, doc, fresh.leaderAccountId(), fresh.leaderAccountId());
        fails(HttpStatus.CONFLICT, "baseline_missing", () -> drafts.generate(CallerContext.human(fresh.leaderAccountId()), fresh.projectId(),
                new Request("" + doc, "" + ver, "보완", null), "k-nobase"));
        verify(ai, never()).beeDraft(any());
    }

    @Test void approvedDocumentWithoutTextIsRefusedWithoutCallingTheAi() {
        long empty = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'PLAN','기획',?) RETURNING id", Long.class, project, leaderId);
        long emptyVersion = jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,1,'APPROVED','x.md','h',NULL,?,?,now()) RETURNING id
                """, Long.class, empty, leaderId, leaderId);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, empty, emptyVersion);
        fails(HttpStatus.CONFLICT, "analysis_not_completed", () -> drafts.generate(member, project, new Request("" + empty, "" + emptyVersion, "보완", null), "k-empty"));
        verify(ai, never()).beeDraft(any());
    }

    @Test void baselineChangedDuringTheCallDiscardsTheDraftButKeepsTheUsage() {
        doAnswer(call -> { publishNewBaseline(false); return reply("# 로그인\n낡은 기준 위의 초안\n"); }).when(ai).beeDraft(any());
        fails(HttpStatus.CONFLICT, "version_conflict", () -> generate("k-race"));
        assertThat(savedCommands()).isZero();       // 낡은 초안을 멱등 기록으로 남기지 않는다
        assertThat(usageRows()).isEqualTo(1);       // 호출은 실제로 일어났으므로 사용량은 남는다
    }

    @Test void aNewBaselineThatKeepsTheSameApprovedVersionStillAcceptsTheDraft() {
        doAnswer(call -> { publishNewBaseline(true); return reply("# 로그인\n같은 승인본 위의 초안\n"); }).when(ai).beeDraft(any());
        assertThat(generate("k-same").afterContent()).contains("같은 승인본");
    }

    // ---- AI 오류 매핑 (D7) ----
    @Test void aiFailuresAreMappedAndEachFailedCallIsRecorded() {
        record Case(RuntimeException error, HttpStatus status, String code) { }
        var cases = List.of(
                new Case(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", HttpHeaders.EMPTY, new byte[0], null), HttpStatus.BAD_GATEWAY, "ai_unavailable"),
                new Case(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x", HttpHeaders.EMPTY, new byte[0], null), HttpStatus.BAD_GATEWAY, "ai_unavailable"),
                new Case(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "x", HttpHeaders.EMPTY, new byte[0], null), HttpStatus.BAD_GATEWAY, "ai_unavailable"),
                new Case(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "x", HttpHeaders.EMPTY, new byte[0], null), HttpStatus.BAD_GATEWAY, "ai_unavailable"),
                new Case(HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "x", HttpHeaders.EMPTY, new byte[0], null), HttpStatus.GATEWAY_TIMEOUT, "ai_timeout"),
                new Case(new ResourceAccessException("read", new SocketTimeoutException("read timed out")), HttpStatus.GATEWAY_TIMEOUT, "ai_timeout"),
                new Case(new ResourceAccessException("read", new HttpTimeoutException("request timed out")), HttpStatus.GATEWAY_TIMEOUT, "ai_timeout"),
                new Case(new ResourceAccessException("connect", new HttpConnectTimeoutException("connect timed out")), HttpStatus.BAD_GATEWAY, "ai_unavailable"),
                new Case(new ResourceAccessException("down", new IOException("refused")), HttpStatus.BAD_GATEWAY, "ai_unavailable"),
                new Case(new IllegalStateException("surprise"), HttpStatus.BAD_GATEWAY, "ai_unavailable"));
        int expected = 0;
        for (var c : cases) {
            doThrow(c.error()).when(ai).beeDraft(any());
            fails(c.status(), c.code(), () -> generate("k-fail-" + UUID.randomUUID()));
            assertThat(failedUsageRows()).as("failed usage after %s", c.error()).isEqualTo(++expected);
        }
        assertThat(savedCommands()).isZero();
        // 실패한 요청을 같은 키로 다시 보내면 AI 를 다시 부른다(실패는 재생하지 않는다)
        doReturn(reply("# 로그인\n복구됨\n")).when(ai).beeDraft(any());
        assertThat(generate("k-fail-0").afterContent()).contains("복구됨");
    }

    @Test void unusableAiOutputIsAnErrorNotATemplate() {
        var withoutResult = new HashMap<String, Object>(reply("x")); withoutResult.remove("result");
        var badOrigin = new HashMap<String, Object>(reply("# 로그인\n본문\n")); badOrigin.put("origin", "GUESS");
        var cases = new ArrayList<Map<String, Object>>(List.of(
                withoutResult, badOrigin,
                reply("   "),                                     // 빈 본문
                reply("# 로그인\n" + "가".repeat(100_001)),         // 제출할 수 없는 크기
                reply("# 로그인\n제어\u0007문자\n"),                 // 제어 문자
                Map.of("result", Map.of("after_content", "# 로그인\n본문\n", "sources", List.of()), "origin", "MODEL")));  // reason 없음
        for (var response : cases) {
            doReturn(response).when(ai).beeDraft(any());
            fails(HttpStatus.BAD_GATEWAY, "ai_unavailable", () -> generate("k-bad-" + UUID.randomUUID()));
        }
        doReturn(null).when(ai).beeDraft(any());
        fails(HttpStatus.BAD_GATEWAY, "ai_unavailable", () -> generate("k-null-" + UUID.randomUUID()));
        assertThat(savedCommands()).isZero();
        assertThat(failedUsageRows()).as("사용량 줄이 없던 응답은 실패 한 줄씩").isGreaterThanOrEqualTo(2);
    }

    // ---- 근거 정리 (D8) ----
    @Test void sourcesOutsideTheRequestAreDroppedAndReported() {
        doReturn(reply("# 로그인\n본문\n", List.of(source(version), source(424242)))).when(ai).beeDraft(any());
        var generation = generate("k-src").generation();
        assertThat(generation.sources()).hasSize(1);
        assertThat(generation.sources().getFirst()).containsEntry("document_version_id", "" + version);
        assertThat(generation.unresolved()).anyMatch(text -> text.contains("근거 1건이 이 요청의 문서·버전과 맞지 않거나"));
    }

    Map<String, Object> src(Object documentId, Object versionId, Object versionNo, Object location) {
        var source = new LinkedHashMap<String, Object>();
        if (documentId != null) source.put("document_id", documentId);
        if (versionId != null) source.put("document_version_id", versionId);
        if (versionNo != null) source.put("version_no", versionNo);
        if (location != null) source.put("location", location);
        return source;
    }

    @Test void everySourceFieldIsCheckedAndKeptSourcesAreNormalized() {
        String doc = "" + document, ver = "" + version;
        doReturn(reply("# 로그인\n본문\n", List.of(
                source(version),                                   // 유효
                src("999999", ver, 1, "L1"),                       // 다른 문서를 가리킴
                src(doc, ver, 2, "L1"),                            // 버전 번호가 다름
                src(doc, ver, 1.5, "L1"),                          // 버전 번호가 정수가 아님
                src(doc, ver, 1, null),                            // location 없음
                src(doc, ver, 1, "  "),                            // location 비어 있음
                src(doc, ver, 1, 7),                               // location 이 문자열이 아님
                src(null, ver, 1, "L1"),                           // document_id 없음
                src(document, version, 1, "L5")))                  // 정수 ID — 같은 값이면 받아 문자열로 맞춘다
        ).when(ai).beeDraft(any());
        var generation = generate("k-src-fields").generation();
        assertThat(generation.sources()).hasSize(2);
        assertThat(generation.sources().get(1)).containsOnlyKeys("document_id", "document_version_id", "version_no", "location")
                .containsEntry("document_id", doc).containsEntry("document_version_id", ver).containsEntry("version_no", 1).containsEntry("location", "L5");
        assertThat(generation.unresolved()).anyMatch(text -> text.contains("근거 7건이"));
    }

    @Test void aDraftWhoseSourcesAreAllMalformedIsRejected() {
        doReturn(reply("# 로그인\n본문\n", List.of(src("" + document, "" + version, 1, null), src("999999", "" + version, 1, "L1"))))
                .when(ai).beeDraft(any());
        fails(HttpStatus.BAD_GATEWAY, "ai_unavailable", () -> generate("k-src-bad"));
        assertThat(savedCommands()).isZero();
    }

    // ---- 호출 중 관련 설계 항목이 바뀐 경우 ----
    @Test void relatedItemGettingANewVersionDuringTheCallDiscardsTheDraft() {
        doAnswer(call -> { newBaseline(true, "newVersion"); return reply("# 로그인\n옛 항목 근거\n"); }).when(ai).beeDraft(any());
        fails(HttpStatus.CONFLICT, "version_conflict", () -> generate("k-item-new"));
        assertThat(savedCommands()).isZero();
        assertThat(usageRows()).isEqualTo(1);
    }

    @Test void relatedItemRetiredDuringTheCallDiscardsTheDraft() {
        doAnswer(call -> { newBaseline(true, "retired"); return reply("# 로그인\n퇴역한 항목 근거\n"); }).when(ai).beeDraft(any());
        fails(HttpStatus.CONFLICT, "version_conflict", () -> generate("k-item-retired"));
        assertThat(savedCommands()).isZero();
    }

    // ---- AI 오류 본문의 사용량 ----
    HttpServerErrorException errorWith(HttpStatus status, String body) {
        return HttpServerErrorException.create(status, "x", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
    String usageLine(String model, int input, int output) {
        return "{\"request_key\":\"err-" + UUID.randomUUID() + "\",\"feature\":\"BEE_DRAFT\",\"model\":\"" + model + "\",\"input_tokens\":" + input
                + ",\"output_tokens\":" + output + ",\"latency_ms\":900,\"failed\":true,\"tokens_known\":true,"
                + "\"estimated_cost_amount\":\"0.0011\",\"currency\":\"USD\"}";
    }

    @Test void usageInTheAiErrorBodyIsRecordedInsteadOfAnUnknownLine() {
        String body = "{\"error\":{\"code\":\"model_unavailable\",\"message\":\"x\"},\"usage\":[" + usageLine("m1", 120, 30) + "," + usageLine("m2", 80, 0) + "]}";
        doThrow(errorWith(HttpStatus.BAD_GATEWAY, body)).when(ai).beeDraft(any());
        fails(HttpStatus.BAD_GATEWAY, "ai_unavailable", () -> generate("k-err-usage"));
        var rows = jdbc.queryForList("SELECT model,tokens_known,input_tokens,output_tokens,estimated_cost_amount,failed FROM llm_usage WHERE project_id=? ORDER BY model", project);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("model", "m1").containsEntry("tokens_known", true).containsEntry("input_tokens", 120)
                .containsEntry("output_tokens", 30).containsEntry("failed", true);
        assertThat(rows.get(1)).containsEntry("model", "m2").containsEntry("input_tokens", 80);
        assertThat(rows).noneMatch(row -> "unknown".equals(row.get("model")));
    }

    @Test void aTimeoutBodyWithUsageIsAlsoKept() {
        doThrow(errorWith(HttpStatus.GATEWAY_TIMEOUT, "{\"usage\":[" + usageLine("m-timeout", 200, 0) + "]}")).when(ai).beeDraft(any());
        fails(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", () -> generate("k-err-timeout"));
        assertThat(jdbc.queryForList("SELECT model FROM llm_usage WHERE project_id=?", String.class, project)).containsExactly("m-timeout");
    }

    @Test void errorBodiesWithoutUsableUsageLeaveOneUnknownLine() {
        int expected = 0;
        for (String body : List.of("", "not json", "{\"error\":{\"code\":\"model_unavailable\"}}", "{\"usage\":\"none\"}", "{\"usage\":[1,2]}", "[]")) {
            doThrow(errorWith(HttpStatus.BAD_GATEWAY, body)).when(ai).beeDraft(any());
            fails(HttpStatus.BAD_GATEWAY, "ai_unavailable", () -> generate("k-err-" + UUID.randomUUID()));
            assertThat(usageRows()).as("body [%s]", body).isEqualTo(++expected);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM llm_usage WHERE project_id=? AND model='unknown' AND NOT tokens_known AND failed",
                Integer.class, project)).isEqualTo(expected);
    }

    // ---- 같은 키의 동시 요청 ----
    <T> Thread start(String name, Callable<T> call, AtomicReference<Object> out) {
        Thread thread = new Thread(() -> {
            try { out.set(call.call()); } catch (Throwable e) { out.set(e); }
        }, name);
        thread.start();
        return thread;
    }
    void awaitWaiting(Thread thread) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end) Thread.sleep(20);
        assertThat(thread.getState()).as("%s 가 먼저 온 요청을 기다리는 중이어야 한다", thread.getName()).isEqualTo(Thread.State.TIMED_WAITING);
    }

    @Test void concurrentRequestsWithTheSameKeyShareOneAiCall() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return reply("# 로그인\n공유된 결과\n");
        }).when(ai).beeDraft(any());
        var firstOut = new AtomicReference<Object>(); var secondOut = new AtomicReference<Object>();
        Thread first = start("leader", () -> generate("k-conc"), firstOut);
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        Thread second = start("follower", () -> generate("k-conc"), secondOut);
        awaitWaiting(second);
        release.countDown();
        first.join(15_000); second.join(15_000);

        assertThat(firstOut.get()).isInstanceOf(Draft.class);
        assertThat(secondOut.get()).isEqualTo(firstOut.get());
        verify(ai, times(1)).beeDraft(any());
        assertThat(usageRows()).isEqualTo(1);
        assertThat(savedCommands()).isEqualTo(1);
    }

    @Test void concurrentRequestsShareTheSameFailureAndTheNextAttemptCallsAgain() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            throw errorWith(HttpStatus.BAD_GATEWAY, "");
        }).when(ai).beeDraft(any());
        var firstOut = new AtomicReference<Object>(); var secondOut = new AtomicReference<Object>();
        Thread first = start("leader", () -> generate("k-conc-fail"), firstOut);
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        Thread second = start("follower", () -> generate("k-conc-fail"), secondOut);
        awaitWaiting(second);
        release.countDown();
        first.join(15_000); second.join(15_000);

        for (var out : List.of(firstOut, secondOut)) {
            assertThat(out.get()).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY); assertThat(e.getCode()).isEqualTo("ai_unavailable");
            });
        }
        verify(ai, times(1)).beeDraft(any());
        assertThat(failedUsageRows()).isEqualTo(1);              // 같은 요청의 실패는 한 번만 센다
        // 진행 중 표시가 남지 않아, 같은 키로 다시 보내면 새로 AI 를 부른다
        doAnswer(call -> reply("# 로그인\n다시 시도\n")).when(ai).beeDraft(any());
        assertThat(generate("k-conc-fail").afterContent()).contains("다시 시도");
        verify(ai, times(2)).beeDraft(any());
    }

    @Test void aDifferentBodyWithTheSameKeyWhileTheFirstIsRunningIsAConflict() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return reply("# 로그인\n첫 요청\n");
        }).when(ai).beeDraft(any());
        var firstOut = new AtomicReference<Object>();
        Thread first = start("leader", () -> generate("k-conc-body"), firstOut);
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        fails(HttpStatus.CONFLICT, "idempotency_conflict", () -> drafts.generate(member, project, request("다른 요청"), "k-conc-body"));
        release.countDown();
        first.join(15_000);
        assertThat(firstOut.get()).isInstanceOf(Draft.class);
        verify(ai, times(1)).beeDraft(any());
    }

    @Test void aDraftWhoseEverySourceIsForeignIsRejected() {
        doReturn(reply("# 로그인\n본문\n", List.of(source(424242)))).when(ai).beeDraft(any());
        fails(HttpStatus.BAD_GATEWAY, "ai_unavailable", () -> generate("k-allforeign"));
        assertThat(savedCommands()).isZero();
    }

    // ---- 멱등 (D9) ----
    @Test void theSameKeyAndBodyReplaysTheStoredDraftWithoutCallingTheAiAgain() {
        Draft first = generate("k-once");
        Draft second = generate("k-once");
        assertThat(second).isEqualTo(first);
        verify(ai, times(1)).beeDraft(any());
        assertThat(usageRows()).isEqualTo(1);
        assertThat(savedCommands()).isEqualTo(1);
        // 같은 키로 다른 내용을 보내면 충돌
        fails(HttpStatus.CONFLICT, "idempotency_conflict", () -> drafts.generate(member, project, request("다른 요청"), "k-once"));
        verify(ai, times(1)).beeDraft(any());
    }

    @Test void aNewKeyIsANewCallAndIsCountedAgain() {
        generate("k-a"); generate("k-b");
        verify(ai, times(2)).beeDraft(any());
        assertThat(usageRows()).isEqualTo(2);
    }

    // ---- 관련 항목 (D10) ----
    @SuppressWarnings("unchecked")
    @Test void relatedItemsAreCappedAtFiftyInKeyOrderAndTheCutIsReported() {
        for (int i = 2; i <= 53; i++) item(String.format("REQ-%03d", i));
        // 퇴역한 항목과 다른 문서가 출처인 항목은 보내지 않는다
        jdbc.update("UPDATE design_item SET retired_at=now() WHERE project_id=? AND key='REQ-002'", project);
        var generation = generate("k-cap").generation();
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai).beeDraft(captor.capture());
        var items = (List<Map<String, Object>>) captor.getValue().get("approved_items");
        assertThat(items).hasSize(50);
        assertThat(items.getFirst().get("key")).isEqualTo("REQ-001");
        assertThat(items.get(1).get("key")).isEqualTo("REQ-003");
        assertThat(items.stream().map(m -> (String) m.get("key")).toList()).isSorted();
        assertThat(generation.unresolved()).anyMatch(text -> text.contains("관련 설계 항목 52개 중 50개만"));
    }

    // ---- 트랜잭션 (D12) ----
    @Test void noTransactionIsOpenWhileTheAiIsCalled() {
        boolean[] transactional = {true};
        // AI 호출은 오래 걸릴 수 있다. 그 동안 트랜잭션(과 프로젝트 행 잠금)이 열려 있으면 다른 요청이 줄줄이 막힌다.
        doAnswer(call -> {
            transactional[0] = TransactionSynchronizationManager.isActualTransactionActive();
            return reply("# 로그인\n잠금 없음\n");
        }).when(ai).beeDraft(any());
        assertThat(generate("k-tx").afterContent()).contains("잠금 없음");
        assertThat(transactional[0]).isFalse();
    }
}
