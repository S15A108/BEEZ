package com.beez.backend.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.dto.MeetingSummaryResponse;
import com.beez.backend.meeting.dto.SummarizeRequest;
import com.beez.backend.meeting.service.MeetingOpenService;
import com.beez.backend.meeting.service.MeetingOpenService.ConflictMeetingCommand;
import com.beez.backend.meeting.service.MeetingSummaryService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S15P31A108-235 MTG-A11 비비 회의 정리, MTG-A12 정리 수정 요청. AI(AI-A08)는 {@link AiClient} 대역이고 어댑터·서비스·저장소는 실제다.
 * 핵심: AI 가 실패하거나 쓸 수 없는 출력을 주면 회의 기록·정리가 하나도 늘지 않고 사용량만 남는다.
 */
class MeetingSummaryIntegrationTest extends IntegrationTestSupport {
    static final String ORIGIN = "http://localhost:5173";
    static final String TEXT = "# 초대\r\n초대는 3일 뒤 만료된다.\r\n링크는 한 번만 쓸 수 있다.\r\n마지막 줄\r\n";

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MeetingOpenService opener;
    @Autowired MeetingSummaryService summaries;
    @MockitoBean AiClient ai;
    TaskApiSupport api;
    long project, leader, member, outsider, baseline, document, version, task1, task2, meeting, brief, e1, e2;

    @BeforeEach void setup() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");
        baseline = jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project);
        document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','초대 정책',?) RETURNING id",
                Long.class, project, leader);
        version = approvedVersion(document, 1, TEXT);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, document, version);
        long item = item("REQ-001");
        long memberMember = api.memberId("member@test");
        task1 = task("T-1", memberMember);
        planWith(task1, item);
        task2 = task("T-2", null);
        meeting = Long.parseLong(opener.openForConflict(new ConflictMeetingCommand(project, List.of(task1, task2), "초대 만료가 충돌",
                "두 작업이 초대 만료 기간을 다르게 가정합니다.", List.of(new MeetingRef("TASK", "" + task1, "T-1")), List.of())).meeting().id());
        brief = jdbc.queryForObject("SELECT min(id) FROM meeting_entry WHERE meeting_id=?", Long.class, meeting);
        e1 = entry("만료는 7일이 좋겠어요.");
        e2 = entry("동의합니다. 7일로 통일해요.");
        jdbc.update("DELETE FROM llm_usage WHERE project_id=?", project);
        doAnswer(call -> reply(List.of(goodChange(2, "초대는 3일 뒤 만료된다.\n", "초대는 7일 뒤 만료된다.")))).when(ai).meetingAnalysis(any());
    }

    // ---- 도우미 ----
    long approvedVersion(long doc, int no, String content) {
        return jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,?,'APPROVED','fixture.md','hash-'||?,?,?,?,now()) RETURNING id
                """, Long.class, doc, no, no, content, leader, leader);
    }
    /** 지금 기준에 들어 있고 출처가 {@link #version} 인 승인 항목 */
    long item(String key) {
        long item = jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,'REQUIREMENT') RETURNING id", Long.class, project, key);
        long itemVersion = jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,
                                                source_document_version_id,source_locator,approved_at)
                VALUES(?,1,'APPROVED',?,1,'{"description":"초대는 만료된다"}','hash-'||?,'HUMAN',?,'{"type":"text_lines","start_line":2,"end_line":2}',now())
                RETURNING id
                """, Long.class, item, key, key, version);
        jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)", baseline, project, item, itemVersion);
        return item;
    }
    long task(String key, Long assigneeMember) {
        return jdbc.queryForObject("INSERT INTO task(project_id,key,title,assignee_member_id,status) VALUES(?,?,'작업',?,'DRAFT') RETURNING id",
                Long.class, project, key, assigneeMember);
    }
    /** 작업의 현재 계획이 설계 항목을 읽는다 */
    void planWith(long task, long designItem) {
        long revision = jdbc.queryForObject("""
                INSERT INTO task_plan_revision(task_id,revision_no,title,goal,input_hash,created_by_account_id)
                VALUES(?,1,'계획','목표','h-'||?,?) RETURNING id
                """, Long.class, task, task, leader);
        long itemVersion = jdbc.queryForObject("SELECT id FROM design_item_version WHERE design_item_id=?", Long.class, designItem);
        jdbc.update("INSERT INTO task_plan_design_ref(plan_revision_id,design_item_id,design_item_version_id,relation) VALUES(?,?,?,'READS')",
                revision, designItem, itemVersion);
        jdbc.update("UPDATE task SET current_plan_revision_id=? WHERE id=?", revision, task);
    }
    long entry(String text) {
        return jdbc.queryForObject("INSERT INTO meeting_entry(meeting_id,kind,author_kind,author_account_id,body) VALUES(?,'TEXT','HUMAN',?,?) RETURNING id",
                Long.class, meeting, leader, text);
    }
    Map<String, Object> goodChange(int line, String before, String after) {
        var locator = new LinkedHashMap<String, Object>();
        locator.put("type", "text_lines");
        locator.put("start_line", line);
        locator.put("end_line", line);
        var change = new LinkedHashMap<String, Object>();
        change.put("document_id", "" + document);
        change.put("base_version_id", "" + version);
        change.put("locator", locator);
        change.put("before", before);
        change.put("after", after);
        change.put("evidence_entry_ids", List.of("" + e1, "" + e2));
        return change;
    }
    Map<String, Object> reply(List<Map<String, Object>> changes) {
        var result = new LinkedHashMap<String, Object>();
        result.put("text", "초대 만료를 7일로 통일하기로 했다.");
        result.put("conflicts", List.of("T-1과 T-2가 초대 만료 기간을 다르게 가정함"));
        result.put("decisions", List.of("초대 만료는 7일로 통일"));
        result.put("document_changes", changes);
        result.put("action_items", List.of(Map.of("text", "만료 상수 수정", "member_id", "" + api.memberId("member@test"))));
        result.put("unresolved", List.of());
        result.put("sources", List.of(Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1, "location", "L2-L2")));
        return Map.of("result", result, "origin", "MODEL", "model", "test-model",
                "usage", List.of(Map.of("request_key", "llm-sum-" + UUID.randomUUID(), "feature", "MEETING_ANALYSIS", "model", "test-model",
                        "input_tokens", 300, "output_tokens", 120, "latency_ms", 1800, "failed", false, "tokens_known", true,
                        "estimated_cost_amount", "0.0042", "currency", "USD")));
    }
    String base() { return "/api/projects/" + project + "/meetings/" + meeting + "/bee-summary"; }
    HttpResponse<String> summarize(Long who, long through, String key) {
        String body = "{\"through_entry_id\":\"" + through + "\"}";
        return key == null ? api.postWithHeaders(base(), body, who, "Origin", ORIGIN)
                : api.postWithHeaders(base(), body, who, "Origin", ORIGIN, "Idempotency-Key", key);
    }
    HttpResponse<String> revise(Long who, long summaryId, String feedback, String key) {
        String body = "{\"feedback\":" + json.writeValueAsString(feedback) + "}";
        return api.postWithHeaders(base() + "/" + summaryId + "/revise", body, who, "Origin", ORIGIN, "Idempotency-Key", key);
    }
    JsonNode body(HttpResponse<String> res) { return json.readTree(res.body()); }
    void expect(HttpResponse<String> res, int status, String code) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        if (code != null) assertThat(body(res).path("error").path("code").asString()).isEqualTo(code);
    }
    int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    int entries() { return count("SELECT count(*) FROM meeting_entry WHERE meeting_id=?", meeting); }
    int summaryRows() { return count("SELECT count(*) FROM meeting_summary WHERE meeting_id=?", meeting); }
    int usageRows() { return count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='MEETING_ANALYSIS'", project); }
    int savedCommands() { return count("SELECT count(*) FROM design_http_command WHERE project_id=?", project); }
    void nothingSaved(int entriesBefore) {
        assertThat(entries()).isEqualTo(entriesBefore);
        assertThat(summaryRows()).isZero();
        assertThat(savedCommands()).isZero();
    }
    void leaderLeaves() { jdbc.update("DELETE FROM meeting_participant WHERE meeting_id=? AND account_id=?", meeting, leader); }

    /** 호출 중에 다른 제안이 발행된 것처럼 문서의 승인본이 새 기준에서 바뀐다 */
    void newBaselineWithNewDocumentVersion() {
        jdbc.update("UPDATE document_version SET status='SUPERSEDED' WHERE id=?", version);
        long next = approvedVersion(document, 2, "# 초대\n다른 제안이 바꾼 본문\n");
        long b2 = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id", Long.class, project, leader);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", b2, project, document, next);
        jdbc.update("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) "
                + "SELECT ?,project_id,design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=?", b2, baseline);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", b2, project);
    }

    // ---- S1 정상 경로 ----
    @Test void summarizesTheConversationAndLeavesASummaryEntryAndRow() throws Exception {
        int before = entries();
        int proposalsBefore = count("SELECT count(*) FROM change_proposal WHERE project_id=?", project);
        var res = summarize(leader, e2, "k-1");
        expect(res, 201, null);
        var s = body(res);
        assertThat(s.path("meeting_id").asString()).isEqualTo("" + meeting);
        assertThat(s.path("through_entry_id").asString()).isEqualTo("" + e2);
        assertThat(s.path("conflicts").get(0).asString()).contains("T-1");
        assertThat(s.path("decisions").get(0).path("text").asString()).isEqualTo("초대 만료는 7일로 통일");
        assertThat(s.path("decisions").get(0).has("decided_by_member_id")).isFalse();
        var change = s.path("document_changes").get(0);
        assertThat(change.path("document_id").asString()).isEqualTo("" + document);
        assertThat(change.path("base_version_id").asString()).isEqualTo("" + version);
        assertThat(change.path("locator").path("type").asString()).isEqualTo("text_lines");
        assertThat(change.path("locator").path("start_line").asInt()).isEqualTo(2);
        assertThat(change.path("before").asString()).isEqualTo("초대는 3일 뒤 만료된다.\n");
        assertThat(change.path("after").asString()).isEqualTo("초대는 7일 뒤 만료된다.");
        assertThat(change.path("evidence_entry_ids").size()).isEqualTo(2);
        assertThat(s.path("action_items").get(0).path("member_id").asString()).isEqualTo("" + api.memberId("member@test"));
        assertThat(s.has("previous_summary_id")).isFalse();
        assertThat(s.path("generation").path("origin").asString()).isEqualTo("MODEL");
        assertThat(s.path("generation").path("model").asString()).isEqualTo("test-model");
        assertThat(s.path("generation").path("text").asString()).contains("7일");
        assertThat(s.path("generation").path("sources").size()).isEqualTo(1);
        assertThat(s.path("generation").path("usage").size()).isEqualTo(1);
        assertThat(s.path("created_at").asString()).isNotBlank();
        // SUMMARY 기록: 비비가 쓴 노란 말풍선 본문, 정리에 쓴 마지막 기록과 생성 근거가 실린다
        var entry = s.path("entry");
        assertThat(entry.path("kind").asString()).isEqualTo("SUMMARY");
        assertThat(entry.path("author_kind").asString()).isEqualTo("BEE");
        assertThat(entry.has("author_account_id")).isFalse();
        assertThat(entry.path("text").asString()).startsWith("회의 정리").contains("충돌:").contains("결정:")
                .contains("바뀔 문서: 문서 1건 · 변경 1곳").contains("할 일:");
        assertThat(entry.path("through_entry_id").asString()).isEqualTo("" + e2);
        assertThat(entry.path("generation").path("origin").asString()).isEqualTo("MODEL");
        assertThat(entries()).isEqualTo(before + 1);
        assertThat(summaryRows()).isEqualTo(1);
        // 문서·기준은 바뀌지 않는다
        assertThat(count("SELECT count(*) FROM change_proposal WHERE project_id=?", project)).isEqualTo(proposalsBefore);
        assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project)).isEqualTo(baseline);
        // 사용량 한 줄(feature MEETING_ANALYSIS), 멱등 기록 한 줄
        var usage = jdbc.queryForMap("SELECT task_id,model,input_tokens,output_tokens,failed,origin FROM llm_usage WHERE project_id=?", project);
        assertThat(usage).containsEntry("model", "test-model").containsEntry("input_tokens", 300).containsEntry("failed", false).containsEntry("origin", "model");
        assertThat(usage.get("task_id")).isNull();
        assertThat(usageRows()).isEqualTo(1);
        assertThat(savedCommands()).isEqualTo(1);
    }

    @Test void theEntriesListShowsThroughEntryAndGenerationOnlyForSummaryEntries() throws Exception {
        summarize(leader, e2, "k-list");
        var res = api.get("/api/projects/" + project + "/meetings/" + meeting + "/entries", leader);
        assertThat(res.statusCode()).isEqualTo(200);
        var items = body(res).path("items");
        var summary = items.get(items.size() - 1);
        assertThat(summary.path("kind").asString()).isEqualTo("SUMMARY");
        assertThat(summary.path("through_entry_id").asString()).isEqualTo("" + e2);
        assertThat(summary.path("generation").path("text").asString()).isNotBlank();
        for (int i = 0; i < items.size() - 1; i++) {
            assertThat(items.get(i).has("through_entry_id")).isFalse();
            assertThat(items.get(i).has("generation")).isFalse();
        }
    }

    // ---- S2 권한·상태 ----
    @Test void onlyTheLeaderWhoParticipatesMayRequest() {
        expect(summarize(member, e2, "k-m"), 403, "forbidden");
        expect(summarize(outsider, e2, "k-o"), 404, "not_found");
        expect(summarize(null, e2, "k-a"), 401, "unauthenticated");
        leaderLeaves();
        expect(summarize(leader, e2, "k-l"), 403, "forbidden");
        expect(revise(member, 1, "고쳐 주세요", "k-rm"), 403, "forbidden");
        assertThat(summaryRows()).isZero();
        assertThat(usageRows()).isZero();
        verify(ai, times(0)).meetingAnalysis(any());
    }

    @Test void aClosedMeetingAndAMissingBaselineAreConflicts() {
        jdbc.update("UPDATE meeting SET status='CLOSED', closed_at=now(), closed_by=? WHERE id=?", leader, meeting);
        expect(summarize(leader, e2, "k-closed"), 409, "meeting_closed");
        jdbc.update("UPDATE meeting SET status='OPEN', closed_at=NULL, closed_by=NULL WHERE id=?", meeting);
        jdbc.update("UPDATE project SET current_baseline_id=NULL WHERE id=?", project);
        expect(summarize(leader, e2, "k-nobase"), 409, "baseline_missing");
        verify(ai, times(0)).meetingAnalysis(any());
    }

    // ---- S3 입력 ----
    @Test void throughEntryMustBeATextOrBriefEntryOfThisMeeting() {
        expect(summarize(leader, 999_999, "k-none"), 404, "not_found");
        // 다른 회의의 기록
        long otherTask = task("T-9", null);
        long other = Long.parseLong(opener.openForConflict(new ConflictMeetingCommand(project, List.of(otherTask), "다른 회의", "x", null, List.of())).meeting().id());
        long foreign = jdbc.queryForObject("SELECT min(id) FROM meeting_entry WHERE meeting_id=?", Long.class, other);
        expect(summarize(leader, foreign, "k-foreign"), 404, "not_found");
        // 비비의 분석·이전 정리는 입력이 될 수 없다
        long analysis = jdbc.queryForObject("INSERT INTO meeting_entry(meeting_id,kind,author_kind,body) VALUES(?,'BEE_ANALYSIS','BEE','분석') RETURNING id", Long.class, meeting);
        expect(summarize(leader, analysis, "k-analysis"), 400, "validation_failed");
        verify(ai, times(0)).meetingAnalysis(any());
    }

    @Test void bodyHeaderAndFeedbackAreValidated() {
        expect(summarize(leader, e2, null), 400, null);
        expect(api.postWithHeaders(base(), "{}", leader, "Origin", ORIGIN, "Idempotency-Key", "k-empty"), 400, "validation_failed");
        expect(api.postWithHeaders(base(), "{\"through_entry_id\":\"abc\"}", leader, "Origin", ORIGIN, "Idempotency-Key", "k-abc"), 400, "validation_failed");
        expect(api.postWithHeaders(base(), "{\"through_entry_id\":\"" + e2 + "\"}", leader, "Idempotency-Key", "k-origin"), 403, null);
        long first = Long.parseLong(body(summarize(leader, e2, "k-first")).path("id").asString());
        expect(revise(leader, first, " ", "k-blank"), 400, "validation_failed");
        expect(revise(leader, first, "x".repeat(4001), "k-long"), 400, "validation_failed");
        expect(revise(leader, 999_999, "고쳐 주세요", "k-nosummary"), 404, "not_found");
        assertThatThrownBy(() -> call("bad\u0001key", e2)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("validation_failed"));
        verify(ai, times(1)).meetingAnalysis(any());
    }

    // ---- S4 AI 요청 내용 ----
    @SuppressWarnings("unchecked")
    @Test void sendsOnlyTextAndBriefEntriesUpToThroughPlusTasksItemsAndNormalizedDocuments() {
        long later = entry("정리 뒤에 나온 말");
        jdbc.queryForObject("INSERT INTO meeting_entry(meeting_id,kind,author_kind,body) VALUES(?,'BEE_ANALYSIS','BEE','분석') RETURNING id", Long.class, meeting);
        expect(summarize(leader, e2, "k-payload"), 201, null);
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai).meetingAnalysis(captor.capture());
        Map<String, Object> sent = captor.getValue();
        assertThat(sent).containsEntry("project_id", "" + project).containsEntry("mode", "SUMMARY");
        assertThat((String) sent.get("request_key")).startsWith("ai-a08:" + project + ":" + leader + ":");
        assertThat(sent).doesNotContainKey("previous_summary").doesNotContainKey("feedback").doesNotContainKey("change_proposal");
        var entries = (List<Map<String, Object>>) sent.get("entries");
        assertThat(entries).extracting(e -> e.get("id")).containsExactly("" + brief, "" + e1, "" + e2).doesNotContain("" + later);
        assertThat(entries).extracting(e -> e.get("kind")).containsExactly("CONFLICT_BRIEF", "TEXT", "TEXT");
        assertThat(entries.get(1)).containsEntry("text", "만료는 7일이 좋겠어요.").containsEntry("author_kind", "HUMAN").containsEntry("meeting_id", "" + meeting);
        var tasks = (List<Map<String, Object>>) sent.get("related_tasks");
        assertThat(tasks).extracting(t -> t.get("key")).containsExactlyInAnyOrder("T-1", "T-2");
        var items = (List<Map<String, Object>>) sent.get("approved_items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("key", "REQ-001").containsEntry("status", "APPROVED");
        assertThat((Map<String, Object>) items.get(0).get("source")).containsEntry("document_version_id", "" + version).containsEntry("location", "L2-L2");
        var documents = (List<Map<String, Object>>) sent.get("documents");
        assertThat(documents).hasSize(1);
        assertThat(documents.get(0)).containsEntry("document_id", "" + document).containsEntry("document_version_id", "" + version);
        // 정규화 원문: CRLF 가 LF 로 바뀌어 있다
        assertThat((String) documents.get(0).get("text")).isEqualTo("# 초대\n초대는 3일 뒤 만료된다.\n링크는 한 번만 쓸 수 있다.\n마지막 줄\n");
    }

    @SuppressWarnings("unchecked")
    @Test void oldestEntriesAreCutFirstWhenThereAreMoreThanTwoHundredAndTheGenerationSaysSo() throws Exception {
        jdbc.update("INSERT INTO meeting_entry(meeting_id,kind,author_kind,author_account_id,body) "
                + "SELECT ?,'TEXT','HUMAN',?,'발언 '||g FROM generate_series(1,210) g", meeting, leader);
        long last = jdbc.queryForObject("SELECT max(id) FROM meeting_entry WHERE meeting_id=?", Long.class, meeting);
        // 잘려 나간 기록(e1, e2)을 근거로 쓴 변경은 검증에서 버려지므로, 이 시험의 AI 는 문서 변경 없이 답한다
        doAnswer(call -> reply(List.of())).when(ai).meetingAnalysis(any());
        var res = summarize(leader, last, "k-many");
        expect(res, 201, null);
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai).meetingAnalysis(captor.capture());
        var entries = (List<Map<String, Object>>) captor.getValue().get("entries");
        assertThat(entries).hasSize(200);
        assertThat(entries.get(entries.size() - 1)).containsEntry("id", "" + last);
        assertThat(entries.get(entries.size() - 1)).containsEntry("text", "발언 210");
        assertThat(entries.get(0)).containsEntry("text", "발언 11");   // 앞쪽(오래된) 기록이 잘렸다
        // 잘린 개수 = 전체(브리프 1 + e1·e2 2 + 210) - 200
        assertThat(body(res).path("generation").path("unresolved").toString()).contains("앞쪽 기록 13건");
    }

    @SuppressWarnings("unchecked")
    @Test void documentsOverTheSizeLimitAreNotSentAndTheGenerationSaysSo() throws Exception {
        long bigDocument = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'API_SPEC','큰 문서',?) RETURNING id",
                Long.class, project, leader);
        long bigVersion = approvedVersion(bigDocument, 1, "가".repeat(100_001));
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, bigDocument, bigVersion);
        long big = jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,'REQ-BIG','REQUIREMENT') RETURNING id", Long.class, project);
        long bigItem = jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,source_document_version_id,approved_at)
                VALUES(?,1,'APPROVED','큰',1,'{"d":"x"}','hash-big','HUMAN',?,now()) RETURNING id
                """, Long.class, big, bigVersion);
        jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)", baseline, project, big, bigItem);
        planWith(task2, big);
        var res = summarize(leader, e2, "k-big");
        expect(res, 201, null);
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai).meetingAnalysis(captor.capture());
        var documents = (List<Map<String, Object>>) captor.getValue().get("documents");
        assertThat(documents).extracting(d -> d.get("document_id")).containsExactly("" + document);
        assertThat(body(res).path("generation").path("unresolved").toString()).contains("관련 문서 1개");
    }

    /** 이 프로젝트에 큰 문서(279,999바이트)를 하나 더 만들어 기준·항목·작업 계획에 연결한다 */
    void bigDocument(String type, String key) {
        long doc = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,?,?,?) RETURNING id",
                Long.class, project, type, key, leader);
        long docVersion = approvedVersion(doc, 1, "가".repeat(93_333));
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, doc, docVersion);
        long item = jdbc.queryForObject("INSERT INTO design_item(project_id,key,kind) VALUES(?,?,'REQUIREMENT') RETURNING id", Long.class, project, key);
        long itemVersion = jdbc.queryForObject("""
                INSERT INTO design_item_version(design_item_id,version_no,status,title,schema_version,content,content_hash,origin,source_document_version_id,approved_at)
                VALUES(?,1,'APPROVED','큰',1,'{"d":"x"}','hash-'||?,'HUMAN',?,now()) RETURNING id
                """, Long.class, item, key, docVersion);
        jdbc.update("INSERT INTO baseline_item VALUES(?,?,?,?)", baseline, project, item, itemVersion);
        Long revision = jdbc.queryForObject("SELECT current_plan_revision_id FROM task WHERE id=?", Long.class, task2);
        if (revision == null) {
            planWith(task2, item);
        } else {
            jdbc.update("INSERT INTO task_plan_design_ref(plan_revision_id,design_item_id,design_item_version_id,relation) VALUES(?,?,?,'READS')",
                    revision, item, itemVersion);
        }
    }

    @SuppressWarnings("unchecked")
    @Test void documentsAndEntriesShareOneByteBudgetAndTheRecentConversationIsReserved() throws Exception {
        bigDocument("PLAN", "REQ-P");
        bigDocument("ERD", "REQ-E");
        bigDocument("API_SPEC", "REQ-A");
        // 한글 3,000자(9,000바이트)짜리 발언 100건 — 글자 수로는 작아 보여도 바이트로는 예산을 넘는다
        jdbc.update("INSERT INTO meeting_entry(meeting_id,kind,author_kind,author_account_id,body) "
                + "SELECT ?,'TEXT','HUMAN',?,repeat('말',2999)||g FROM generate_series(1,100) g", meeting, leader);
        long last = jdbc.queryForObject("SELECT max(id) FROM meeting_entry WHERE meeting_id=?", Long.class, meeting);
        doAnswer(call -> reply(List.of())).when(ai).meetingAnalysis(any());
        var res = summarize(leader, last, "k-budget");
        expect(res, 201, null);
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai).meetingAnalysis(captor.capture());
        Map<String, Object> sent = captor.getValue();
        // 실제 직렬화 바이트가 목표 예산(900,000) 안이다
        assertThat(json.writeValueAsString(sent).getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(900_000);
        // 문서는 통째로만 — 작은 문서와 큰 문서 둘이 들어가고 셋째 큰 문서는 빠진다
        var documents = (List<Map<String, Object>>) sent.get("documents");
        assertThat(documents).hasSize(3);
        assertThat(documents).allMatch(d -> !((String) d.get("text")).isEmpty());
        // 가장 최근 발언은 항상 들어가고, 최근 기록 예약분(100,000바이트 ≈ 11건)은 문서가 예산을 써도 남는다
        var entries = (List<Map<String, Object>>) sent.get("entries");
        assertThat(entries.get(entries.size() - 1)).containsEntry("id", "" + last);
        assertThat(entries.size()).isGreaterThanOrEqualTo(11).isLessThan(100);
        // 오래된 쪽부터 빠졌고 빠진 것은 사람이 읽는 문장으로 남는다
        long cut = 103 - entries.size();
        var unresolved = body(res).path("generation").path("unresolved").toString();
        assertThat(unresolved).contains("관련 문서 1개").contains("앞쪽 기록 " + cut + "건");
    }

    // ---- S5 출력 검증 ----
    @Test void badItemsAreDroppedAndTheGenerationCountsThem() throws Exception {
        doAnswer(call -> reply(List.of(goodChange(2, "초대는 3일 뒤 만료된다.\n", "초대는 7일 뒤 만료된다."),
                goodChange(3, "원문과 다른 문장", "x"), goodChange(2, "초대는 3일 뒤 만료된다.\n", "겹치는 두 번째")))).when(ai).meetingAnalysis(any());
        var res = summarize(leader, e2, "k-partial");
        expect(res, 201, null);
        assertThat(body(res).path("document_changes").size()).isEqualTo(1);
        assertThat(body(res).path("generation").path("unresolved").toString()).contains("문서 변경 2건");
        assertThat(summaryRows()).isEqualTo(1);
    }

    @Test void anUnusableOutputIsAnAiFailureAndSavesNothing() {
        int before = entries();
        doAnswer(call -> reply(List.of(goodChange(3, "원문과 다른 문장", "x")))).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-allbad"), 502, "ai_unavailable");
        nothingSaved(before);
        assertThat(usageRows()).isEqualTo(1);   // 모델은 불렸으니 사용량은 남는다
        // 구조 불량, origin 불량
        doAnswer(call -> Map.of("origin", "MODEL")).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-nostruct"), 502, "ai_unavailable");
        doAnswer(call -> {
            Map<String, Object> r = new LinkedHashMap<>(reply(List.of()));
            r.put("origin", "GUESS");
            return r;
        }).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-origin"), 502, "ai_unavailable");
        nothingSaved(before);
    }

    // ---- S6 AI 오류 ----
    record Case(Throwable error, int status, String code) { }

    @Test void aiFailuresAreClassifiedAndSaveNothing() {
        int before = entries();
        var cases = List.of(
                new Case(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", HttpHeaders.EMPTY, new byte[0], null), 502, "ai_unavailable"),
                new Case(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "x", HttpHeaders.EMPTY, new byte[0], null), 502, "ai_unavailable"),
                new Case(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "x", HttpHeaders.EMPTY, new byte[0], null), 502, "ai_unavailable"),
                // AI 서버가 SUMMARY 계약을 아직 받지 않을 때의 거절(422)
                new Case(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "x", HttpHeaders.EMPTY, "SUMMARY contract is not agreed".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), 502, "ai_unavailable"),
                new Case(HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "x", HttpHeaders.EMPTY, new byte[0], null), 504, "ai_timeout"),
                new Case(new ResourceAccessException("read", new SocketTimeoutException("read timed out")), 504, "ai_timeout"),
                new Case(new ResourceAccessException("read", new HttpTimeoutException("request timed out")), 504, "ai_timeout"),
                new Case(new ResourceAccessException("connect", new HttpConnectTimeoutException("connect timed out")), 502, "ai_unavailable"),
                new Case(new ResourceAccessException("down", new IOException("refused")), 502, "ai_unavailable"),
                new Case(new IllegalStateException("boom"), 502, "ai_unavailable"));
        int calls = 0;
        for (Case c : cases) {
            doThrow(c.error()).when(ai).meetingAnalysis(any());
            expect(summarize(leader, e2, "k-err-" + calls), c.status(), c.code());
            assertThat(usageRows()).as("case %s", c.error()).isEqualTo(++calls);
            nothingSaved(before);
        }
        assertThat(count("SELECT count(*) FROM llm_usage WHERE project_id=? AND feature='MEETING_ANALYSIS' AND model='unknown' AND NOT tokens_known AND failed", project))
                .isEqualTo(calls);
    }

    HttpServerErrorException errorWith(HttpStatus status, String body) {
        return HttpServerErrorException.create(status, "x", HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
    String usageLine(String model, int input, int output) {
        return "{\"request_key\":\"err-" + UUID.randomUUID() + "\",\"feature\":\"MEETING_ANALYSIS\",\"model\":\"" + model + "\",\"input_tokens\":" + input
                + ",\"output_tokens\":" + output + ",\"latency_ms\":900,\"failed\":true,\"tokens_known\":true,"
                + "\"estimated_cost_amount\":\"0.0011\",\"currency\":\"USD\"}";
    }

    @Test void usageInTheAiErrorBodyIsRecordedInsteadOfAnUnknownLine() {
        String body = "{\"error\":{\"code\":\"model_unavailable\",\"message\":\"x\"},\"usage\":[" + usageLine("m1", 120, 30) + "," + usageLine("m2", 80, 0) + "]}";
        doThrow(errorWith(HttpStatus.BAD_GATEWAY, body)).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-err-usage"), 502, "ai_unavailable");
        var rows = jdbc.queryForList("SELECT model,tokens_known,input_tokens,output_tokens,failed FROM llm_usage WHERE project_id=? ORDER BY model", project);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("model", "m1").containsEntry("tokens_known", true).containsEntry("input_tokens", 120).containsEntry("failed", true);
        assertThat(rows.get(1)).containsEntry("model", "m2").containsEntry("input_tokens", 80);
        assertThat(rows).noneMatch(row -> "unknown".equals(row.get("model")));
        assertThat(summaryRows()).isZero();
    }

    // ---- S7 수정 요청 ----
    @SuppressWarnings("unchecked")
    @Test void reviseMakesANewLatestSummaryAndKeepsThePreviousOne() throws Exception {
        long first = Long.parseLong(body(summarize(leader, e2, "k-a")).path("id").asString());
        long e3 = entry("아니요, 14일로 하기로 했어요.");
        doAnswer(call -> reply(List.of(goodChange(2, "초대는 3일 뒤 만료된다.\n", "초대는 14일 뒤 만료된다.")))).when(ai).meetingAnalysis(any());
        var res = revise(leader, first, "만료 기간은 7일이 아니라 14일로 정했어.", "k-b");
        expect(res, 201, null);
        var second = body(res);
        assertThat(second.path("id").asString()).isNotEqualTo("" + first);
        assertThat(second.path("previous_summary_id").asString()).isEqualTo("" + first);
        assertThat(second.path("feedback").asString()).isEqualTo("만료 기간은 7일이 아니라 14일로 정했어.");
        assertThat(second.path("document_changes").get(0).path("after").asString()).contains("14일");
        // 다시 정리할 때는 지금까지의 모든 기록을 본다
        assertThat(second.path("through_entry_id").asString()).isEqualTo("" + e3);
        assertThat(second.path("entry").path("through_entry_id").asString()).isEqualTo("" + e3);
        assertThat(summaryRows()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM meeting_entry WHERE meeting_id=? AND kind='SUMMARY'", meeting)).isEqualTo(2);
        // 이전 정리는 그대로 남는다
        assertThat(jdbc.queryForObject("SELECT result::text FROM meeting_summary WHERE id=?", String.class, first)).contains("7일");
        // AI 에는 이전 정리와 피드백이 가고, 이전 SUMMARY 기록은 entries 에 없다
        var captor = ArgumentCaptor.forClass(Map.class);
        verify(ai, times(2)).meetingAnalysis(captor.capture());
        Map<String, Object> sent = captor.getAllValues().get(1);
        assertThat(sent).containsEntry("feedback", "만료 기간은 7일이 아니라 14일로 정했어.");
        var previous = (Map<String, Object>) sent.get("previous_summary");
        assertThat(previous).containsEntry("id", "" + first).containsKey("document_changes").containsKey("generation");
        assertThat((List<Map<String, Object>>) sent.get("entries")).extracting(e -> e.get("kind")).doesNotContain("SUMMARY");
        assertThat(usageRows()).isEqualTo(2);
    }

    @Test void onlyTheLatestSummaryCanBeRevised() throws Exception {
        long first = Long.parseLong(body(summarize(leader, e2, "k-a")).path("id").asString());
        long second = Long.parseLong(body(revise(leader, first, "고쳐 주세요", "k-b")).path("id").asString());
        int before = summaryRows();
        expect(revise(leader, first, "또 고쳐 주세요", "k-c"), 409, "summary_outdated");
        assertThat(summaryRows()).isEqualTo(before);
        expect(revise(leader, second, "고쳐 주세요 2", "k-d"), 201, null);
        verify(ai, times(3)).meetingAnalysis(any());   // 409 는 AI 를 부르기 전에 걸러졌다
    }

    @Test void aSummaryOfAnotherMeetingIsNotFound() throws Exception {
        long first = Long.parseLong(body(summarize(leader, e2, "k-a")).path("id").asString());
        long otherTask = task("T-9", null);
        long other = Long.parseLong(opener.openForConflict(new ConflictMeetingCommand(project, List.of(otherTask), "다른 회의", "x", null, List.of())).meeting().id());
        var res = api.postWithHeaders("/api/projects/" + project + "/meetings/" + other + "/bee-summary/" + first + "/revise",
                "{\"feedback\":\"고쳐 주세요\"}", leader, "Origin", ORIGIN, "Idempotency-Key", "k-x");
        expect(res, 404, "not_found");
    }

    // ---- S8 호출 중 변경 ----
    @Test void aDocumentApprovedVersionChangedDuringTheCallDiscardsTheSummary() {
        int before = entries();
        doAnswer(call -> {
            newBaselineWithNewDocumentVersion();
            return reply(List.of(goodChange(2, "초대는 3일 뒤 만료된다.\n", "초대는 7일 뒤 만료된다.")));
        }).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-stale"), 409, "summary_outdated");
        nothingSaved(before);
        assertThat(usageRows()).isEqualTo(1);
    }

    @Test void aSummaryWithoutDocumentChangesIsNotAffectedByANewBaseline() {
        doAnswer(call -> {
            newBaselineWithNewDocumentVersion();
            return reply(List.of());
        }).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-nochange"), 201, null);
    }

    @Test void theMeetingClosingOrTheLeaderLeavingDuringTheCallSavesNothing() {
        int before = entries();
        doAnswer(call -> {
            jdbc.update("UPDATE meeting SET status='CLOSED', closed_at=now(), closed_by=? WHERE id=?", leader, meeting);
            return reply(List.of());
        }).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-closing"), 409, "meeting_closed");
        nothingSaved(before);
        jdbc.update("UPDATE meeting SET status='OPEN', closed_at=NULL, closed_by=NULL WHERE id=?", meeting);
        doAnswer(call -> {
            leaderLeaves();
            return reply(List.of());
        }).when(ai).meetingAnalysis(any());
        expect(summarize(leader, e2, "k-leaving"), 403, "forbidden");
        nothingSaved(before);
    }

    @Test void aNewerSummaryCreatedDuringARevisionMakesItOutdated() throws Exception {
        long first = Long.parseLong(body(summarize(leader, e2, "k-a")).path("id").asString());
        var nested = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> {
            // 다른 요청이 먼저 정리를 새로 만들었다(안쪽 호출은 한 번만)
            if (nested.compareAndSet(false, true)) {
                summaries.summarize(CallerContext.human(leader), project, "" + meeting, new SummarizeRequest("" + e2), "k-inner");
            }
            return reply(List.of());
        }).when(ai).meetingAnalysis(any());
        int before = summaryRows();
        expect(revise(leader, first, "고쳐 주세요", "k-b"), 409, "summary_outdated");
        assertThat(summaryRows()).isEqualTo(before + 1);   // 안쪽 요청이 만든 것 하나뿐
    }

    // ---- S9 동시 요청·멱등 ----
    <T> Thread start(String name, Callable<T> call, AtomicReference<Object> out) {
        Thread thread = new Thread(() -> {
            try {
                out.set(call.call());
            } catch (Throwable e) {
                out.set(e);
            }
        }, name);
        thread.start();
        return thread;
    }
    void awaitWaiting(Thread thread) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < end) Thread.sleep(20);
        assertThat(thread.getState()).as("%s 가 먼저 온 요청을 기다리는 중이어야 한다", thread.getName()).isEqualTo(Thread.State.TIMED_WAITING);
    }
    MeetingSummaryResponse call(String key, long through) {
        return summaries.summarize(CallerContext.human(leader), project, "" + meeting, new SummarizeRequest("" + through), key);
    }

    @Test void concurrentRequestsWithTheSameKeyShareOneAiCallAndOneSummary() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return reply(List.of());
        }).when(ai).meetingAnalysis(any());
        var firstOut = new AtomicReference<Object>();
        var secondOut = new AtomicReference<Object>();
        Thread first = start("leader", () -> call("k-conc", e2), firstOut);
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        Thread second = start("follower", () -> call("k-conc", e2), secondOut);
        awaitWaiting(second);
        release.countDown();
        first.join(15_000);
        second.join(15_000);

        assertThat(firstOut.get()).isInstanceOf(MeetingSummaryResponse.class);
        assertThat(secondOut.get()).isEqualTo(firstOut.get());
        verify(ai, times(1)).meetingAnalysis(any());
        assertThat(summaryRows()).isEqualTo(1);
        assertThat(usageRows()).isEqualTo(1);
        assertThat(savedCommands()).isEqualTo(1);
    }

    @Test void followersShareTheLeadersFailureAndASecondTryRunsAgain() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            throw HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "x", HttpHeaders.EMPTY, new byte[0], null);
        }).when(ai).meetingAnalysis(any());
        var firstOut = new AtomicReference<Object>();
        var secondOut = new AtomicReference<Object>();
        Thread first = start("leader", () -> call("k-shared-fail", e2), firstOut);
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        Thread second = start("follower", () -> call("k-shared-fail", e2), secondOut);
        awaitWaiting(second);
        release.countDown();
        first.join(15_000);
        second.join(15_000);
        assertThat(firstOut.get()).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        assertThat(secondOut.get()).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("ai_unavailable"));
        verify(ai, times(1)).meetingAnalysis(any());
        assertThat(summaryRows()).isZero();
        // 실패는 저장되지 않아 같은 키로 다시 시도하면 다시 실행된다
        doAnswer(call -> reply(List.of())).when(ai).meetingAnalysis(any());
        assertThat(call("k-shared-fail", e2)).isNotNull();
        assertThat(summaryRows()).isEqualTo(1);
    }

    @Test void sameKeyWithADifferentBodyIsAConflictWhileInFlightAndAfterwards() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
            return reply(List.of());
        }).when(ai).meetingAnalysis(any());
        var firstOut = new AtomicReference<Object>();
        Thread first = start("leader", () -> call("k-diff", e2), firstOut);
        assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> call("k-diff", e1)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("idempotency_conflict"));
        release.countDown();
        first.join(15_000);
        assertThat(firstOut.get()).isInstanceOf(MeetingSummaryResponse.class);
        assertThatThrownBy(() -> call("k-diff", e1)).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("idempotency_conflict"));
    }

    @Test void theSameKeyAgainReturnsTheFirstResponseWithoutCallingTheAiOrSavingAgain() throws Exception {
        var first = summarize(leader, e2, "k-replay");
        expect(first, 201, null);
        var again = summarize(leader, e2, "k-replay");
        expect(again, 201, null);
        assertThat(body(again)).isEqualTo(body(first));
        verify(ai, times(1)).meetingAnalysis(any());
        assertThat(summaryRows()).isEqualTo(1);
        assertThat(usageRows()).isEqualTo(1);
        // 같은 키라도 정리가 끝난 회의에서는 처음 응답을 그대로 돌려준다
        jdbc.update("UPDATE meeting SET status='CLOSED', closed_at=now(), closed_by=? WHERE id=?", leader, meeting);
        expect(summarize(leader, e2, "k-replay"), 201, null);
        expect(summarize(leader, e2, "k-new"), 409, "meeting_closed");
    }

    // ---- S10 기록 순서 ----
    @Test void entriesWrittenAfterTheSummaryDoNotChangeItAndTheSummaryEntryComesAfterThoseItCovers() throws Exception {
        var res = summarize(leader, e2, "k-order");
        long summaryEntry = Long.parseLong(body(res).path("entry").path("id").asString());
        assertThat(summaryEntry).isGreaterThan(e2);
        String stored = jdbc.queryForObject("SELECT result::text FROM meeting_summary WHERE meeting_id=?", String.class, meeting);
        long later = entry("정리 뒤의 말");
        assertThat(later).isGreaterThan(summaryEntry);
        assertThat(jdbc.queryForObject("SELECT result::text FROM meeting_summary WHERE meeting_id=?", String.class, meeting)).isEqualTo(stored);
        assertThat(jdbc.queryForObject("SELECT through_entry_id FROM meeting_summary WHERE meeting_id=?", Long.class, meeting)).isEqualTo(e2);
        // 비비의 정리가 쓰는 SUMMARY 기록은 다른 정리의 입력이 아니므로, 같은 기록 범위의 정리를 다시 요청해도 SUMMARY 가 섞이지 않는다
        var captor = ArgumentCaptor.forClass(Map.class);
        summarize(leader, later, "k-order-2");
        verify(ai, times(2)).meetingAnalysis(captor.capture());
        @SuppressWarnings("unchecked") var entries = (List<Map<String, Object>>) captor.getAllValues().get(1).get("entries");
        assertThat(entries).extracting(e -> e.get("kind")).doesNotContain("SUMMARY");
        List<Object> ids = new ArrayList<>();
        entries.forEach(e -> ids.add(e.get("id")));
        assertThat(ids).doesNotContain("" + summaryEntry);
    }

    // ---- S11 새로 고쳐도 이어지게: SUMMARY 기록의 summary_id 와 최신 정리 조회(MTG-A15) ----
    HttpResponse<String> latest(Long who) { return api.get(base(), who); }

    @Test void theSummaryEntryCarriesTheSummaryIdSoTheLatestSummaryCanBeFoundAgainAfterAReload() throws Exception {
        var made = body(summarize(leader, e2, "k-id"));
        String summaryId = made.path("id").asString();
        // 기록 목록(MTG-A10)의 SUMMARY 기록에 정리 ID 가 실린다. 사람 기록에는 없다.
        var listed = body(api.get("/api/projects/" + project + "/meetings/" + meeting + "/entries", leader)).path("items");
        boolean found = false;
        for (JsonNode entry : listed) {
            if ("SUMMARY".equals(entry.path("kind").asString())) {
                found = true;
                assertThat(entry.path("summary_id").asString()).isEqualTo(summaryId);
                assertThat(entry.path("through_entry_id").asString()).isEqualTo("" + e2);
            } else {
                assertThat(entry.has("summary_id")).as("사람 기록에는 summary_id 가 없다").isFalse();
            }
        }
        assertThat(found).isTrue();
        // 응답의 entry 에도 같은 값이 있다
        assertThat(made.path("entry").path("summary_id").asString()).isEqualTo(summaryId);
    }

    @Test void latestSummaryReturnsTheStructuredNewestSummaryToAnyProjectMemberAndIsNotFoundBeforeTheFirst() throws Exception {
        expect(latest(leader), 404, "not_found");
        var first = body(summarize(leader, e2, "k-l1"));
        var read = latest(member);
        expect(read, 200, null);
        assertThat(body(read).path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(body(read).path("document_changes").size()).isEqualTo(1);
        assertThat(body(read).path("decisions").get(0).path("text").asString()).isEqualTo("초대 만료는 7일로 통일");
        // 수정 요청으로 새 정리가 생기면 그것이 최신이다. 이전 정리는 previous_summary_id 로 이어진다
        var revised = body(revise(leader, Long.parseLong(first.path("id").asString()), "14일로 정했어요.", "k-l2"));
        var newest = body(latest(leader));
        assertThat(newest.path("id").asString()).isEqualTo(revised.path("id").asString());
        assertThat(newest.path("previous_summary_id").asString()).isEqualTo(first.path("id").asString());
        assertThat(newest.path("entry").path("summary_id").asString()).isEqualTo(revised.path("id").asString());
    }

    @Test void latestSummaryIsHiddenFromNonMembersAndReadableAfterTheMeetingIsClosed() throws Exception {
        summarize(leader, e2, "k-l3");
        expect(latest(outsider), 404, "not_found");
        jdbc.update("UPDATE meeting SET status='CLOSED', closed_at=now(), closed_by=? WHERE id=?", leader, meeting);
        expect(latest(member), 200, null);
    }
}
