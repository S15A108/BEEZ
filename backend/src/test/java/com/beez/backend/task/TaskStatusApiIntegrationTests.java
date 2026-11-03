package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 작업·검증·에이전트 현황 조회 (S15P31A108-64): MON-T01·MON-V01·MON-A05 */
class TaskStatusApiIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    Long outsider;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");
    }

    private String base() {
        return "/api/projects/" + projectId;
    }

    /** 작업 생성 → {taskId, revisionId, lockVersion} */
    private long[] createTask(String title, String refsJson) throws Exception {
        HttpResponse<String> res = api.post(base() + "/tasks", api.planJson(title, refsJson, "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        return new long[] {taskId, revisionId, TaskApiSupport.longField(res.body(), "lock_version")};
    }

    private long state(long taskId, String event, long revisionId, long lock, Long validationId, String extra) throws Exception {
        StringBuilder sb = new StringBuilder("{\"event\":\"").append(event).append("\",\"plan_revision_id\":\"").append(revisionId)
                .append("\",\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":\"").append(validationId).append("\"");
        }
        if (extra != null) {
            sb.append(",").append(extra);
        }
        HttpResponse<String> res = api.post(base() + "/tasks/" + taskId + "/state-events", sb.append("}").toString(), member);
        assertThat(res.statusCode()).as(res.body()).isLessThan(300);
        return TaskApiSupport.longField(res.body(), "lock_version");
    }

    private JsonNode get(String path, Long account, int status) throws Exception {
        HttpResponse<String> res = api.get(path, account);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
        return JSON.readTree(res.body());
    }

    private static Map<String, Integer> counts(JsonNode list, String keyField) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (JsonNode n : list) {
            out.put(n.get(keyField).asText(), n.get("count").asInt());
        }
        return out;
    }

    private long session(Long tokenId, Long taskId) {
        return api.jdbc.sql("""
                        insert into agent_session (agent_token_id, session_key, current_task_id, last_report_at)
                        values (:k, 'sess-' || :k || '-' || coalesce(:t, 0), :t, now()) returning id
                        """).param("k", tokenId).param("t", taskId, java.sql.Types.BIGINT).query(Long.class).single();
    }

    @Test
    void taskSummary_countsStatusesAndWaitingReasons_withZeroRows() throws Exception {
        // READY 1, IN_PROGRESS 1, WAITING(APPROVAL) 1, COMPLETED 1, CANCELLED 1
        createTask("ready", api.readsRef("API-01"));
        long[] running = createTask("running", api.readsRef("API-01"));
        state(running[0], "START", running[1], running[2], api.passValidation(running[0]), null);
        long[] waiting = createTask("waiting", api.readsRef("API-01"));
        long lock = state(waiting[0], "START", waiting[1], waiting[2], api.passValidation(waiting[0]), null);
        state(waiting[0], "WAIT", waiting[1], lock, null, "\"waiting_reason\":\"APPROVAL\"");
        long[] done = createTask("done", api.readsRef("API-01"));
        long lockDone = state(done[0], "START", done[1], done[2], api.passValidation(done[0]), null);
        state(done[0], "COMPLETED", done[1], lockDone, null, "\"summary\":\"끝\",\"delta\":[]");
        long[] cancelled = createTask("cancelled", api.readsRef("API-01"));
        state(cancelled[0], "CANCEL", cancelled[1], cancelled[2], null, null);

        JsonNode body = get(base() + "/tasks/summary", member, 200);
        Map<String, Integer> byStatus = counts(body.get("by_status"), "status");
        assertThat(byStatus).containsExactly(Map.entry("DRAFT", 0), Map.entry("READY", 1), Map.entry("IN_PROGRESS", 1),
                Map.entry("WAITING", 1), Map.entry("COMPLETED", 1), Map.entry("CANCELLED", 1));
        Map<String, Integer> byReason = counts(body.get("by_waiting_reason"), "waiting_reason");
        assertThat(byReason).containsEntry("APPROVAL", 1).containsEntry("INPUT", 0).containsEntry("DEPENDENCY", 0)
                .containsEntry("REVALIDATION", 0);
        // 프로젝트 밖 사람은 404
        get(base() + "/tasks/summary", outsider, 404);
        get(base() + "/tasks/summary", null, 401);
    }

    @Test
    void validationSummary_countsLatestValidationOfCurrentPlan_perTask() throws Exception {
        // PASS 1 (SQL), NOT_RUN 1 (검증 없음), RUNNING 1 (의미 분석 대기), STALE 1 (PASS 뒤 계획 변경)
        long[] passed = createTask("passed", api.readsRef("API-01"));
        api.passValidation(passed[0]);
        createTask("none", api.readsRef("API-01"));
        long[] running = createTask("running", api.implementsRef("API-02"));
        HttpResponse<String> accepted = api.post(base() + "/tasks/" + running[0] + "/validations",
                "{\"plan_revision_id\":\"" + running[1] + "\",\"expected_lock_version\":" + running[2] + "}", member);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
        long[] stale = createTask("stale", api.readsRef("API-01"));
        long lock = state(stale[0], "START", stale[1], stale[2], api.passValidation(stale[0]), null);
        // 완료 기준을 바꾸는 계획 변경 (검증 입력이 바뀜). 제목만 바꾸면 검증을 이어 받아 PASS 로 센다 (-162)
        String plan = api.planJson("stale2", api.readsRef("API-01"), "[]").trim()
                .replace("\"completion_criteria\": [\"테스트 통과\"]", "\"completion_criteria\": [\"테스트 통과\", \"문서 갱신\"]");
        String revision = plan.substring(0, plan.lastIndexOf('}')) + ",\n  \"expected_revision_no\": 1,\n  \"expected_lock_version\": " + lock + "\n}";
        HttpResponse<String> revised = api.post(base() + "/tasks/" + stale[0] + "/plan-revisions", revision, member);
        assertThat(revised.statusCode()).as(revised.body()).isEqualTo(201);
        // 계획이 바뀐 작업은 새 계획에 검증이 없으므로 NOT_RUN 으로 센다 (옛 계획의 STALE 은 현재 계획이 아니다)

        JsonNode body = get(base() + "/validations/summary", leader, 200);
        Map<String, Integer> byStatus = counts(body.get("by_status"), "status");
        assertThat(byStatus.keySet()).containsExactly("NOT_RUN", "RUNNING", "PASS", "NEEDS_INPUT", "NEEDS_REVIEW", "STALE", "ERROR");
        assertThat(byStatus).containsEntry("PASS", 1).containsEntry("NOT_RUN", 2).containsEntry("RUNNING", 1)
                .containsEntry("STALE", 0).containsEntry("ERROR", 0);
        int total = 0;
        for (int c : byStatus.values()) {
            total += c;
        }
        assertThat(total).isEqualTo(4);                                            // 작업마다 한 번씩
        get(base() + "/validations/summary", outsider, 404);
    }

    @Test
    void agentWorkStatus_showsReportedTaskOnly_filtersByMember_andPages() throws Exception {
        long[] working = createTask("working", api.implementsRef("API-01"));
        long lock = state(working[0], "START", working[1], working[2], api.passValidation(working[0]), null);
        state(working[0], "WAIT", working[1], lock, null, "\"waiting_reason\":\"INPUT\"");
        long[] planned = createTask("planned", api.readsRef("API-01"));          // 검증 없음 → NOT_RUN
        Long memberToken = api.agentTokenId();
        Long leaderToken = api.leaderAgentTokenId();
        long idle = session(memberToken, null);                                      // 보고된 작업 없음
        long onWorking = session(memberToken, working[0]);
        long onPlanned = session(leaderToken, planned[0]);
        // 폐기된 토큰의 세션은 나오지 않는다
        api.jdbc.sql("""
                        insert into agent_token (project_id, account_id, name, token_hash, revoked_at)
                        values (:p, :a, 'old-laptop', 'hash-old', now())
                        """).param("p", projectId).param("a", member).update();
        Long revoked = api.jdbc.sql("select id from agent_token where name = 'old-laptop'").query(Long.class).single();
        session(revoked, working[0]);
        // 활성이 아닌 멤버(나간 사람)의 세션도 나오지 않는다 — 토큰이 남아 있어도
        api.jdbc.sql("""
                        insert into project_member (project_id, account_id, role, active, left_at)
                        values (:p, (select id from account where email = 'outsider@test'), 'MEMBER', false, now())
                        """).param("p", projectId).update();
        api.jdbc.sql("""
                        insert into agent_token (project_id, account_id, name, token_hash)
                        values (:p, (select id from account where email = 'outsider@test'), 'gone-laptop', 'hash-gone')
                        """).param("p", projectId).update();
        Long gone = api.jdbc.sql("select id from agent_token where name = 'gone-laptop'").query(Long.class).single();
        session(gone, null);

        JsonNode all = get(base() + "/agents/work-status", leader, 200);
        assertThat(all.get("has_more").asBoolean()).isFalse();
        assertThat(all.get("items")).hasSize(3);                                     // 최신 세션부터
        JsonNode first = all.get("items").get(0);
        assertThat(first.get("agent_session_id").asLong()).isEqualTo(onPlanned);
        assertThat(first.get("member_id").asLong()).isEqualTo(api.memberId("leader@test"));
        assertThat(first.get("agent_name").asText()).isEqualTo("leader-laptop");
        assertThat(first.get("current_task_id").asLong()).isEqualTo(planned[0]);
        assertThat(first.get("task_title").asText()).isEqualTo("planned");
        assertThat(first.get("task_status").asText()).isEqualTo("READY");
        assertThat(first.get("validation_status").asText()).isEqualTo("NOT_RUN");
        assertThat(first.has("waiting_reason")).isFalse();
        JsonNode second = all.get("items").get(1);
        assertThat(second.get("agent_session_id").asLong()).isEqualTo(onWorking);
        assertThat(second.get("task_status").asText()).isEqualTo("WAITING");
        assertThat(second.get("waiting_reason").asText()).isEqualTo("INPUT");
        assertThat(second.get("validation_status").asText()).isEqualTo("PASS");
        JsonNode third = all.get("items").get(2);
        assertThat(third.get("agent_session_id").asLong()).isEqualTo(idle);
        assertThat(third.has("current_task_id")).isFalse();                         // 보고가 없으면 추정하지 않는다
        assertThat(third.has("task_status")).isFalse();
        assertThat(third.has("validation_status")).isFalse();

        // 멤버 필터
        JsonNode mine = get(base() + "/agents/work-status?member_id=" + api.memberId("member@test"), member, 200);
        assertThat(mine.get("items")).hasSize(2);
        // 페이지
        JsonNode page1 = get(base() + "/agents/work-status?limit=2", member, 200);
        assertThat(page1.get("items")).hasSize(2);
        assertThat(page1.get("has_more").asBoolean()).isTrue();
        JsonNode page2 = get(base() + "/agents/work-status?limit=2&cursor=" + page1.get("next_cursor").asText(), member, 200);
        assertThat(page2.get("items")).hasSize(1);
        assertThat(page2.get("items").get(0).get("agent_session_id").asLong()).isEqualTo(idle);
        assertThat(page2.get("has_more").asBoolean()).isFalse();
        // 형식 오류·권한
        get(base() + "/agents/work-status?member_id=abc", member, 400);
        get(base() + "/agents/work-status", outsider, 404);
    }
}
