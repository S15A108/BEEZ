package com.beez.backend.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.beez.backend.ai.AiClient;
import com.beez.backend.changeproposal.dto.ProposalDtos.DocumentChange;
import com.beez.backend.changeproposal.dto.ProposalDtos.Input;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.document.storage.DocumentStorage;
import com.beez.backend.meeting.dto.MeetingRef;
import com.beez.backend.meeting.service.MeetingOpenService;
import com.beez.backend.meeting.service.MeetingOpenService.ConflictMeetingCommand;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S15P31A108-297 MTG-A13 회의 끝내기와 변경 제안 생성. AI(AI-A08)는 {@link AiClient} 대역으로 정리를 만들고, 끝내기는 AI 를 부르지 않는다.
 * 핵심: 팀장·참여자만, 제안은 회의당 1건, 정리가 오래됐으면 회의를 열어 둔 채 제안을 만들지 않고, 종료로 문서·기준은 바뀌지 않는다.
 */
@Import(MeetingCloseIntegrationTest.Support.class)
class MeetingCloseIntegrationTest extends IntegrationTestSupport {
    static final String ORIGIN = "http://localhost:5173";
    static final String TEXT = "# 초대\r\n초대는 3일 뒤 만료된다.\r\n링크는 한 번만 쓸 수 있다.\r\n마지막 줄\r\n";
    /** 정리 한 건(2행 교체)을 적용한 결과. 정규화한 LF 본문이다 */
    static final String APPLIED = "# 초대\n초대는 7일 뒤 만료된다.\n링크는 한 번만 쓸 수 있다.\n마지막 줄\n";

    static class MemoryStorage implements DocumentStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        @Override public String store(byte[] bytes, String extension) {
            String key = "close-test/" + UUID.randomUUID() + "." + extension;
            objects.put(key, bytes.clone());
            return key;
        }
        @Override public byte[] read(String key) throws IOException {
            byte[] bytes = objects.get(key);
            if (bytes == null) throw new IOException("missing " + key);
            return bytes.clone();
        }
        @Override public void delete(String key) { objects.remove(key); }
    }

    @TestConfiguration static class Support {
        @Bean @Primary MemoryStorage memoryStorage() { return new MemoryStorage(); }
    }

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MeetingOpenService opener;
    @Autowired MemoryStorage storage;
    @MockitoSpyBean ProposalCommandService proposalCommands;
    @MockitoBean AiClient ai;
    TaskApiSupport api;
    long project, leader, member, baseline, document, version, task1, task2, meeting, e1, e2;
    long memberMemberId;

    @BeforeEach void setup() {
        storage.objects.clear();
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        project = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        memberMemberId = api.memberId("member@test");
        baseline = jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project);
        document = jdbc.queryForObject("INSERT INTO document(project_id,type,title,created_by) VALUES(?,'REQ_SPEC','초대 정책',?) RETURNING id",
                Long.class, project, leader);
        version = approvedVersion(document, 1, TEXT);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", baseline, project, document, version);
        long item = item("REQ-001");
        task1 = task("T-1", memberMemberId);
        planWith(task1, item);
        task2 = task("T-2", null);
        meeting = Long.parseLong(opener.openForConflict(new ConflictMeetingCommand(project, List.of(task1, task2), "초대 만료가 충돌",
                "두 작업이 초대 만료 기간을 다르게 가정합니다.", List.of(new MeetingRef("TASK", "" + task1, "T-1")), List.of())).meeting().id());
        e1 = entry("만료는 7일이 좋겠어요.");
        e2 = entry("동의합니다. 7일로 통일해요.");
        jdbc.update("DELETE FROM llm_usage WHERE project_id=?", project);
        aiReturns(List.of(change(2, "초대는 3일 뒤 만료된다.\n", "초대는 7일 뒤 만료된다.")));
    }

    // ---- 도우미 ----
    long approvedVersion(long doc, int no, String content) {
        return jdbc.queryForObject("""
                INSERT INTO document_version(document_id,version_no,status,storage_key,content_hash,content_text,created_by,approved_by,approved_at)
                VALUES(?,?,'APPROVED','fixture.md','hash-'||?,?,?,?,now()) RETURNING id
                """, Long.class, doc, no, no, content, leader, leader);
    }
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
    Map<String, Object> change(int line, String before, String after) {
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
    void aiReturns(List<Map<String, Object>> changes) {
        var result = new LinkedHashMap<String, Object>();
        result.put("text", "초대 만료를 7일로 통일하기로 했다.");
        result.put("conflicts", List.of("T-1과 T-2가 초대 만료 기간을 다르게 가정함"));
        result.put("decisions", List.of("초대 만료는 7일로 통일"));
        result.put("document_changes", changes);
        result.put("action_items", List.of());
        result.put("unresolved", List.of());
        result.put("sources", List.of(Map.of("document_id", "" + document, "document_version_id", "" + version, "version_no", 1, "location", "L2-L2")));
        var reply = Map.of("result", result, "origin", "MODEL", "model", "test-model",
                "usage", List.of(Map.of("request_key", "llm-sum-" + UUID.randomUUID(), "feature", "MEETING_ANALYSIS", "model", "test-model",
                        "input_tokens", 300, "output_tokens", 120, "latency_ms", 1800, "failed", false, "tokens_known", true,
                        "estimated_cost_amount", "0.0042", "currency", "USD")));
        doAnswer(call -> reply).when(ai).meetingAnalysis(any());
    }
    String meetingPath() { return "/api/projects/" + project + "/meetings/" + meeting; }
    /** 정리를 만들고 summary_id 를 돌려준다 */
    long summarize(long through) {
        var res = api.postWithHeaders(meetingPath() + "/bee-summary", "{\"through_entry_id\":\"" + through + "\"}", leader,
                "Origin", ORIGIN, "Idempotency-Key", "sum-" + UUID.randomUUID());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        return Long.parseLong(body(res).path("id").asString());
    }
    HttpResponse<String> close(Long who, long summaryId, String key) {
        return api.postWithHeaders(meetingPath() + "/close", "{\"summary_id\":\"" + summaryId + "\"}", who,
                "Origin", ORIGIN, "Idempotency-Key", key);
    }
    JsonNode body(HttpResponse<String> res) { return json.readTree(res.body()); }
    void expect(HttpResponse<String> res, int status, String code) {
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        if (code != null) assertThat(body(res).path("error").path("code").asString()).isEqualTo(code);
    }
    int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    int proposals() { return count("SELECT count(*) FROM change_proposal WHERE project_id=?", project); }
    int liveProposals() { return count("SELECT count(*) FROM change_proposal WHERE meeting_id=? AND status<>'WITHDRAWN'", meeting); }
    String meetingStatus() { return jdbc.queryForObject("SELECT status FROM meeting WHERE id=?", String.class, meeting); }
    void stillOpenWithoutProposal() {
        assertThat(meetingStatus()).isEqualTo("OPEN");
        assertThat(proposals()).isZero();
        assertThat(count("SELECT count(*) FROM activity_event WHERE event_type='MEETING_CLOSED' AND project_id=?", project)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND project_id=?", project)).isZero();
    }
    Input proposalInput(String after) {
        return new Input("" + baseline, "회의 결과", "이유", null, null, List.of(),
                List.of(new DocumentChange("" + document, "" + version, after, null)), null, null, null);
    }

    // ---- 정상 경로 ----
    @Test void closesTheMeetingAndCreatesExactlyOneProposalFromTheSummary() throws Exception {
        long summary = summarize(e2);
        var res = close(leader, summary, "close-1");
        expect(res, 200, null);
        var out = body(res);
        assertThat(out.path("meeting").path("status").asString()).isEqualTo("CLOSED");
        assertThat(out.path("meeting").path("closed_at").asString()).isNotBlank();
        assertThat(out.path("meeting").path("closed_by").asString()).isEqualTo("" + leader);
        String proposalId = out.path("change_proposal_id").asString();
        assertThat(proposalId).isNotBlank();
        assertThat(out.path("meeting").path("change_proposal_id").asString()).isEqualTo(proposalId);

        // 제안: 끝낸 팀장이 올린 사람(HUMAN), 회의 출처, 작업·항목 없음, 문서 변경만, 원문은 정리를 줄 단위로 적용한 결과
        var row = jdbc.queryForMap("SELECT status,proposer_kind,proposer_account_id,proposer_token_id,task_id,validation_id,meeting_id,title,reason,base_baseline_id FROM change_proposal WHERE id=?", Long.parseLong(proposalId));
        assertThat(row).containsEntry("status", "PROPOSED").containsEntry("proposer_kind", "HUMAN")
                .containsEntry("proposer_account_id", leader).containsEntry("meeting_id", meeting).containsEntry("base_baseline_id", baseline);
        assertThat(row.get("proposer_token_id")).isNull();
        assertThat(row.get("task_id")).isNull();
        assertThat(row.get("validation_id")).isNull();
        assertThat((String) row.get("title")).startsWith("회의 결과: ").contains("초대 만료가 충돌".substring(0, 2));
        assertThat((String) row.get("reason")).contains("초대 만료는 7일로 통일");
        assertThat(count("SELECT count(*) FROM change_proposal_item WHERE change_proposal_id=?", Long.parseLong(proposalId))).isZero();
        var draft = jdbc.queryForMap("""
                SELECT v.id,v.status,v.content_text,v.parent_version_id,v.change_proposal_id FROM document_version v
                WHERE v.document_id=? AND v.status='DRAFT'
                """, document);
        assertThat(draft).containsEntry("content_text", APPLIED).containsEntry("parent_version_id", version)
                .containsEntry("change_proposal_id", Long.parseLong(proposalId));
        assertThat(storage.objects).hasSize(1);

        // 제안 조회 응답에도 회의 출처가 보인다
        var proposal = api.get("/api/projects/" + project + "/change-proposals/" + proposalId, leader);
        assertThat(proposal.statusCode()).as(proposal.body()).isEqualTo(200);
        assertThat(body(proposal).path("proposal").path("meeting_id").asString()).isEqualTo("" + meeting);

        // 문서와 기준은 종료로 바뀌지 않는다
        assertThat(jdbc.queryForObject("SELECT current_baseline_id FROM project WHERE id=?", Long.class, project)).isEqualTo(baseline);
        assertThat(jdbc.queryForObject("SELECT status FROM document_version WHERE id=?", String.class, version)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT content_text FROM document_version WHERE id=?", String.class, version)).isEqualTo(TEXT);
        assertThat(count("SELECT count(*) FROM baseline WHERE project_id=?", project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT change_proposal_id FROM meeting WHERE id=?", Long.class, meeting)).isEqualTo(Long.parseLong(proposalId));
    }

    @Test void notifiesTheTaskOwnersExceptTheLeaderAndRecordsTheActivity() throws Exception {
        long summary = summarize(e2);
        var out = body(close(leader, summary, "close-n"));
        long proposal = Long.parseLong(out.path("change_proposal_id").asString());
        // T-1 의 담당자(member)만 받는다. T-2 는 담당자가 없고, 끝낸 팀장은 받지 않는다
        assertThat(out.path("notified_member_ids").size()).isEqualTo(1);
        assertThat(out.path("notified_member_ids").get(0).asString()).isEqualTo("" + memberMemberId);
        var notes = jdbc.queryForList("SELECT recipient_account_id,type,ref_kind,ref_id,dedupe_key FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND project_id=?", project);
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0)).containsEntry("recipient_account_id", member).containsEntry("ref_kind", "CHANGE_PROPOSAL")
                .containsEntry("ref_id", proposal).containsEntry("dedupe_key", "meeting:" + meeting + ":closed:to:" + member);
        // 제출 알림은 따로 가지 않는다(같은 사건을 두 번 알리지 않는다)
        assertThat(count("SELECT count(*) FROM notification WHERE type='CHANGE_PROPOSAL_SUBMITTED' AND project_id=?", project)).isZero();
        // 활동: 끝낸 팀장(HUMAN)이 회의를 끝냈고 제안이 연결된다
        var activity = jdbc.queryForMap("SELECT actor_kind,actor_id,ref_kind,ref_id,change_proposal_id,dedupe_key FROM activity_event WHERE event_type='MEETING_CLOSED' AND project_id=?", project);
        assertThat(activity).containsEntry("actor_kind", "HUMAN").containsEntry("actor_id", leader).containsEntry("ref_kind", "MEETING")
                .containsEntry("ref_id", meeting).containsEntry("change_proposal_id", proposal).containsEntry("dedupe_key", "meeting:" + meeting + ":closed");
    }

    @Test void theSameKeyReturnsTheFirstResponseWithoutAnotherProposalNotificationOrActivity() throws Exception {
        long summary = summarize(e2);
        var first = close(leader, summary, "close-replay");
        var again = close(leader, summary, "close-replay");
        expect(first, 200, null);
        expect(again, 200, null);
        assertThat(body(again)).isEqualTo(body(first));
        assertThat(proposals()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND project_id=?", project)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM activity_event WHERE event_type='MEETING_CLOSED' AND project_id=?", project)).isEqualTo(1);
    }

    @Test void aDifferentKeyAfterClosingIsRejectedAndLeavesOneProposal() throws Exception {
        long summary = summarize(e2);
        expect(close(leader, summary, "close-a"), 200, null);
        expect(close(leader, summary, "close-b"), 409, "meeting_closed");
        assertThat(proposals()).isEqualTo(1);
    }

    @Test void concurrentClosesWithDifferentKeysMakeOneProposalAndCloseOnce() throws Exception {
        long summary = summarize(e2);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "close-race-" + i;
            Callable<HttpResponse<String>> call = () -> { start.await(); return close(leader, summary, key); };
            results.add(pool.submit(call));
        }
        start.countDown();
        int ok = 0, closed = 0;
        for (var future : results) {
            var res = future.get();
            if (res.statusCode() == 200) ok++;
            else { expect(res, 409, "meeting_closed"); closed++; }
        }
        pool.shutdownNow();
        assertThat(ok).isEqualTo(1);
        assertThat(closed).isEqualTo(threads - 1);
        assertThat(proposals()).isEqualTo(1);
        assertThat(liveProposals()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM activity_event WHERE event_type='MEETING_CLOSED' AND project_id=?", project)).isEqualTo(1);
        // 지는 쪽이 올려 둔 원문은 참조되지 않아 남지 않는다
        assertThat(storage.objects).hasSize(1);
    }

    // ---- 권한 ----
    @Test void onlyTheLeaderWhoParticipatesCanClose() throws Exception {
        long summary = summarize(e2);
        expect(close(member, summary, "close-m"), 403, null);
        stillOpenWithoutProposal();
        // 팀장이 회의 참여자가 아니면 403
        jdbc.update("DELETE FROM meeting_participant WHERE meeting_id=? AND account_id=?", meeting, leader);
        expect(close(leader, summary, "close-p"), 403, null);
        stillOpenWithoutProposal();
    }

    @Test void aSummaryOfAnotherMeetingIsNotFound() throws Exception {
        long summary = summarize(e2);
        var res = api.postWithHeaders("/api/projects/" + project + "/meetings/" + meeting + "/close", "{\"summary_id\":\"999999\"}", leader,
                "Origin", ORIGIN, "Idempotency-Key", "close-x");
        expect(res, 404, null);
        stillOpenWithoutProposal();
    }

    @Test void requiresASummaryId() {
        var res = api.postWithHeaders(meetingPath() + "/close", "{}", leader, "Origin", ORIGIN, "Idempotency-Key", "close-empty");
        expect(res, 400, "validation_failed");
        stillOpenWithoutProposal();
    }

    // ---- 정리가 오래됨 ----
    @Test void aNewHumanEntryAfterTheSummaryBlocksClosing() throws Exception {
        long summary = summarize(e2);
        entry("잠깐, 5일로 하죠.");
        expect(close(leader, summary, "close-new"), 409, "summary_outdated");
        stillOpenWithoutProposal();
        assertThat(storage.objects).isEmpty();
    }

    @Test void onlyTheLatestSummaryCanBeUsed() throws Exception {
        long old = summarize(e2);
        long latest = summarize(e2);
        expect(close(leader, old, "close-old"), 409, "summary_outdated");
        stillOpenWithoutProposal();
        expect(close(leader, latest, "close-latest"), 200, null);
    }

    @Test void aBaselineChangedAfterTheSummaryBlocksClosing() throws Exception {
        long summary = summarize(e2);
        // 정리한 뒤 다른 제안이 발행돼 문서의 승인본이 새 기준에서 바뀐다
        jdbc.update("UPDATE document_version SET status='SUPERSEDED' WHERE id=?", version);
        long next = approvedVersion(document, 2, "# 초대\n다른 제안이 바꾼 본문\n");
        long b2 = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id", Long.class, project, leader);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", b2, project, document, next);
        jdbc.update("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) "
                + "SELECT ?,project_id,design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=?", b2, baseline);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", b2, project);
        expect(close(leader, summary, "close-base"), 409, "summary_outdated");
        stillOpenWithoutProposal();
    }

    // ---- 문서 변경이 없거나 너무 큼 ----
    @Test void aSummaryWithoutDocumentChangesClosesWithoutAProposal() throws Exception {
        aiReturns(List.of());
        long summary = summarize(e2);
        var res = close(leader, summary, "close-none");
        expect(res, 200, null);
        var out = body(res);
        assertThat(out.path("meeting").path("status").asString()).isEqualTo("CLOSED");
        assertThat(out.has("change_proposal_id")).isFalse();
        assertThat(out.path("notified_member_ids").size()).isZero();
        assertThat(proposals()).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND project_id=?", project)).isZero();
        var activity = jdbc.queryForMap("SELECT change_proposal_id FROM activity_event WHERE event_type='MEETING_CLOSED' AND project_id=?", project);
        assertThat(activity.get("change_proposal_id")).isNull();
        assertThat(storage.objects).isEmpty();
    }

    @Test void aResultOverTheSubmissionLimitIsRejectedAndTheMeetingStaysOpen() throws Exception {
        aiReturns(List.of(change(2, "초대는 3일 뒤 만료된다.\n", "가".repeat(100_000))));
        long summary = summarize(e2);
        expect(close(leader, summary, "close-big"), 400, "validation_failed");
        stillOpenWithoutProposal();
        assertThat(storage.objects).isEmpty();
    }

    // ---- 제안은 만들었는데 종료가 실패한 뒤의 재시도 ----
    @Test void aRetryReusesTheLiveProposalWhenTheSummaryIsUnchanged() throws Exception {
        long summary = summarize(e2);
        // 종료 직전에 실패한 상황: 제안만 이미 만들어져 있다
        var earlier = proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting, proposalInput(APPLIED));
        assertThat(storage.objects).hasSize(1);
        var out = body(close(leader, summary, "close-retry"));
        assertThat(out.path("change_proposal_id").asString()).isEqualTo(earlier.id());
        assertThat(proposals()).isEqualTo(1);
        assertThat(liveProposals()).isEqualTo(1);
        // 새로 올린 원문은 참조되지 않아 지워지고 처음 것만 남는다
        assertThat(storage.objects).hasSize(1);
    }

    @Test void aRetryWithdrawsTheOldProposalWhenTheSummaryChanged() throws Exception {
        var earlier = proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting,
                proposalInput("# 초대\n초대는 5일 뒤 만료된다.\n링크는 한 번만 쓸 수 있다.\n마지막 줄\n"));
        long summary = summarize(e2);
        var out = body(close(leader, summary, "close-changed"));
        String created = out.path("change_proposal_id").asString();
        assertThat(created).isNotEqualTo(earlier.id());
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(earlier.id()))).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(created))).isEqualTo("PROPOSED");
        assertThat(liveProposals()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM change_proposal WHERE meeting_id=?", meeting)).isEqualTo(2);
    }

    @Test void aRetryDoesNotReuseAProposalMadeOnAnOlderBaseline() throws Exception {
        var earlier = proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting, proposalInput(APPLIED));
        // 종료가 실패한 사이 정리가 인용하지 않은 다른 문서의 발행으로 기준만 바뀌었다(인용 문서 버전은 그대로)
        long b2 = jdbc.queryForObject("INSERT INTO baseline(project_id,version_no,published_by) VALUES(?,2,?) RETURNING id", Long.class, project, leader);
        jdbc.update("INSERT INTO baseline_document VALUES(?,?,?,?)", b2, project, document, version);
        jdbc.update("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) "
                + "SELECT ?,project_id,design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=?", b2, baseline);
        jdbc.update("UPDATE project SET current_baseline_id=? WHERE id=?", b2, project);
        long summary = summarize(e2);
        var out = body(close(leader, summary, "close-new-baseline"));
        String created = out.path("change_proposal_id").asString();
        assertThat(created).isNotEqualTo(earlier.id());
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(earlier.id()))).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT base_baseline_id FROM change_proposal WHERE id=?", Long.class, Long.parseLong(created))).isEqualTo(b2);
        assertThat(liveProposals()).isEqualTo(1);
    }

    @Test void aLateCleanupOfAFailedCloseDoesNotWithdrawTheProposalAnotherRetryAlreadyClosedWith() throws Exception {
        long first = summarize(e2);
        var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var secondEntry = new java.util.concurrent.atomic.AtomicLong();
        // 요청 A: 제안을 만든 직후 새 기록이 들어와 종료가 summary_outdated 로 실패한다
        doAnswer(call -> {
            Object created = call.callRealMethod();
            if (armed.getAndSet(false)) secondEntry.set(entry("방금 한 말인데요, 7일이 맞아요."));
            return created;
        }).when(proposalCommands).createFromMeeting(any(), anyLong(), anyLong(), any());
        // A 의 뒤늦은 정리가 돌기 전에, 새 정리로 재시도한 요청 B 가 같은 제안을 재사용해 회의를 닫는다
        var retried = new java.util.concurrent.atomic.AtomicReference<HttpResponse<String>>();
        doAnswer(call -> {
            long second = summarize(secondEntry.get());
            retried.set(close(leader, second, "close-b"));
            return call.callRealMethod();
        }).when(proposalCommands).withdrawMeetingProposal(any(), anyLong(), anyLong(), anyLong());

        expect(close(leader, first, "close-a"), 409, "summary_outdated");

        expect(retried.get(), 200, null);
        String proposal = body(retried.get()).path("change_proposal_id").asString();
        assertThat(meetingStatus()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT change_proposal_id FROM meeting WHERE id=?", Long.class, meeting)).isEqualTo(Long.parseLong(proposal));
        // A 의 정리가 B 가 닫은 회의의 제안을 거두면 안 된다
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(proposal))).isEqualTo("PROPOSED");
        assertThat(proposals()).isEqualTo(1);
        assertThat(liveProposals()).isEqualTo(1);
    }

    @Test void aRetryDoesNotCloseTheMeetingOnAProposalThatWasWithdrawnAfterItWasReused() throws Exception {
        var earlier = proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting, proposalInput(APPLIED));
        long summary = summarize(e2);
        var armed = new java.util.concurrent.atomic.AtomicBoolean(true);
        // 재시도가 제안을 재사용해 돌려받은 직후, 앞선 요청의 뒤늦은 정리가 그 제안을 거둔다
        doAnswer(call -> {
            Object reused = call.callRealMethod();
            if (armed.getAndSet(false)) {
                proposalCommands.withdrawMeetingProposal(CallerContext.human(leader), project, meeting, Long.parseLong(earlier.id()));
            }
            return reused;
        }).when(proposalCommands).createFromMeeting(any(), anyLong(), anyLong(), any());

        expect(close(leader, summary, "close-reused"), 409, "meeting_proposal_changed");
        assertThat(meetingStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT change_proposal_id FROM meeting WHERE id=?", Long.class, meeting)).isNull();
        // 다시 시도하면 거둔 제안 대신 새로 만들어 닫힌다
        var again = body(close(leader, summary, "close-again"));
        assertThat(again.path("change_proposal_id").asString()).isNotEqualTo(earlier.id());
        assertThat(meetingStatus()).isEqualTo("CLOSED");
        assertThat(liveProposals()).isEqualTo(1);
    }

    @Test void aRetryDoesNotTieTheMeetingToARejectedProposal() throws Exception {
        var earlier = proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting, proposalInput(APPLIED));
        proposalCommands.reject(CallerContext.human(leader), project, Long.parseLong(earlier.id()),
                new com.beez.backend.changeproposal.dto.ProposalDtos.RejectRequest("보완", earlier.revision(), earlier.snapshotHash()), "reject-earlier");
        long summary = summarize(e2);
        var out = body(close(leader, summary, "close-after-reject"));
        String created = out.path("change_proposal_id").asString();
        assertThat(created).isNotEqualTo(earlier.id());
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(earlier.id()))).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(created))).isEqualTo("PROPOSED");
        assertThat(liveProposals()).isEqualTo(1);
        // 알림은 반려된 제안이 아니라 새 제안을 가리킨다
        assertThat(count("SELECT count(*) FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND ref_id=?", Long.parseLong(earlier.id()))).isZero();
    }

    @Test void aRetryWhoseSummaryHasNoDocumentChangesWithdrawsTheProposalOfThePreviousAttempt() throws Exception {
        // 첫 시도: 제안은 만들었는데 종료가 실패했다. 재시도 때의 새 정리에는 문서 변경이 없다
        var earlier = proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting, proposalInput(APPLIED));
        aiReturns(List.of());
        long summary = summarize(e2);
        var res = close(leader, summary, "close-no-change-retry");
        expect(res, 200, null);
        assertThat(body(res).has("change_proposal_id")).isFalse();
        assertThat(meetingStatus()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE id=?", String.class, Long.parseLong(earlier.id()))).isEqualTo("WITHDRAWN");
        assertThat(liveProposals()).isZero();
        assertThat(jdbc.queryForObject("SELECT change_proposal_id FROM meeting WHERE id=?", Long.class, meeting)).isNull();
        assertThat(count("SELECT count(*) FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND project_id=?", project)).isZero();
    }

    @Test void aPublicationOverlappingTheEndOfTheMeetingMakesTheSummaryOutdatedInsteadOfClosingOnAStaleBaseline() throws Exception {
        long summary = summarize(e2);
        long next = approvedVersion(document, 2, "# 초대\n다른 제안이 바꾼 본문\n");
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 발행: 프로젝트 잠금을 쥔 채 새 기준을 만들고, 풀어 주면 커밋한다
            Callable<Void> publication = () -> {
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (var statement = connection.createStatement()) {
                        statement.execute("SELECT id FROM project WHERE id=" + project + " FOR UPDATE");
                        holding.countDown();
                        assertThat(release.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        statement.execute("UPDATE document_version SET status='SUPERSEDED' WHERE id=" + version);
                        long b2;
                        try (var rs = statement.executeQuery("INSERT INTO baseline(project_id,version_no,published_by) VALUES("
                                + project + ",2," + leader + ") RETURNING id")) {
                            rs.next();
                            b2 = rs.getLong(1);
                        }
                        statement.execute("INSERT INTO baseline_document VALUES(" + b2 + "," + project + "," + document + "," + next + ")");
                        statement.execute("INSERT INTO baseline_item(baseline_id,project_id,design_item_id,design_item_version_id) "
                                + "SELECT " + b2 + ",project_id,design_item_id,design_item_version_id FROM baseline_item WHERE baseline_id=" + baseline);
                        statement.execute("UPDATE project SET current_baseline_id=" + b2 + " WHERE id=" + project);
                    }
                    connection.commit();
                }
                return null;
            };
            var publishing = new java.util.concurrent.atomic.AtomicReference<Future<Void>>();
            // 제안을 만든 직후 = 종료 직전에 발행이 프로젝트 잠금을 잡는다(제안 생성이 끝난 뒤에 시작해야 생성이 막히지 않는다)
            doAnswer(call -> {
                Object created = call.callRealMethod();
                publishing.set(pool.submit(publication));
                assertThat(holding.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return created;
            }).when(proposalCommands).createFromMeeting(any(), anyLong(), anyLong(), any());

            Future<HttpResponse<String>> closing = pool.submit(() -> close(leader, summary, "close-vs-publication"));
            Thread.sleep(1500);
            // 종료는 기준을 확인하기 전에 발행이 끝나기를 기다린다
            assertThat(closing.isDone()).as("종료가 발행의 프로젝트 잠금을 기다리지 않고 끝났다").isFalse();
            release.countDown();
            publishing.get().get(30, java.util.concurrent.TimeUnit.SECONDS);
            expect(closing.get(30, java.util.concurrent.TimeUnit.SECONDS), 409, "summary_outdated");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(meetingStatus()).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT change_proposal_id FROM meeting WHERE id=?", Long.class, meeting)).isNull();
        assertThat(liveProposals()).isZero();
    }

    @Test void aHumanEntryThatArrivesWhileTheProposalIsBeingMadeWithdrawsItAndKeepsTheMeetingOpen() throws Exception {
        long summary = summarize(e2);
        // 제안을 만든 직후, 회의를 닫기 전에 새 기록이 들어온다
        doAnswer(call -> {
            Object proposal = call.callRealMethod();
            entry("방금 한 말인데요, 5일이 맞아요.");
            return proposal;
        }).when(proposalCommands).createFromMeeting(any(), anyLong(), anyLong(), any());
        expect(close(leader, summary, "close-race-entry"), 409, "summary_outdated");
        assertThat(meetingStatus()).isEqualTo("OPEN");
        // 만든 제안은 거둔다(쓸 곳이 없다). 활동·알림은 남지 않는다
        assertThat(proposals()).isEqualTo(1);
        assertThat(liveProposals()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM change_proposal WHERE meeting_id=?", String.class, meeting)).isEqualTo("WITHDRAWN");
        assertThat(count("SELECT count(*) FROM activity_event WHERE event_type='MEETING_CLOSED' AND project_id=?", project)).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE type='MEETING_PROPOSAL_CREATED' AND project_id=?", project)).isZero();
        assertThat(jdbc.queryForObject("SELECT change_proposal_id FROM meeting WHERE id=?", Long.class, meeting)).isNull();
    }

    @Test void theUniqueIndexAllowsOneLiveProposalPerMeeting() {
        proposalCommands.createFromMeeting(CallerContext.human(leader), project, meeting, proposalInput(APPLIED));
        long second = jdbc.queryForObject("SELECT id FROM change_proposal WHERE meeting_id=?", Long.class, meeting);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO change_proposal(project_id,key,status,base_baseline_id,proposer_kind,proposer_account_id,title,reason,meeting_id)
                VALUES(?,'CP-X','PROPOSED',?,'HUMAN',?,'t','r',?)
                """, project, baseline, leader, meeting)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(second).isPositive();
    }

    // ---- 공개 CHG-A01 은 meeting_id 를 받지 않는다 ----
    @Test void thePublicProposalApiIgnoresMeetingId() throws Exception {
        String bodyJson = "{\"base_baseline_id\":\"" + baseline + "\",\"title\":\"공개 제안\",\"reason\":\"이유\",\"items\":[],"
                + "\"documents\":[{\"document_id\":\"" + document + "\",\"base_document_version_id\":\"" + version + "\","
                + "\"after_content\":\"# 초대\\n공개 제안 본문\\n\"}],\"meeting_id\":\"" + meeting + "\"}";
        var res = api.postWithHeaders("/api/projects/" + project + "/change-proposals", bodyJson, leader, "Origin", ORIGIN,
                "Idempotency-Key", "public-1");
        assertThat(res.statusCode()).as(res.body()).isIn(200, 201);
        var created = body(res);
        assertThat(created.has("meeting_id")).isFalse();
        assertThat(count("SELECT count(*) FROM change_proposal WHERE meeting_id IS NOT NULL AND project_id=?", project)).isZero();
    }
}
