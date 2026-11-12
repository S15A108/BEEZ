package com.beez.backend.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.context.dto.ContextRecordResponse;
import com.beez.backend.context.dto.VersionRefDto;
import com.beez.backend.context.dto.WriteContextArgs;
import com.beez.backend.context.entity.ContextRecordKind;
import com.beez.backend.context.entity.Visibility;
import com.beez.backend.mcp.service.ContextMcpService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * CTX-A02 공유 작업 기록 목록·검색, CTX-A03 상세 (S15P31A108-404). 실제 HTTP 로 사람 세션에서 부른다.
 * 에이전트가 MCP write_context 로 남긴 기록이 웹 '작업 기록'에 보이는지, 남의 나만 보기 기록과 다른 프로젝트 기록은 안 보이는지 본다.
 */
class ContextRecordRestIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ContextMcpService mcp;

    @Autowired
    tools.jackson.databind.ObjectMapper json;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    Long outsider;
    CallerContext memberAgent;
    CallerContext leaderAgent;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");
        memberAgent = CallerContext.agent(member, api.agentTokenId());
        leaderAgent = CallerContext.agent(leader, api.leaderAgentTokenId());
    }

    private String base() {
        return "/api/projects/" + projectId + "/context-records";
    }

    private long createTask(String title, Long account) {
        HttpResponse<String> created = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson(title, api.readsRef("API-01"), "[]"), account);
        assertThat(created.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(created.body(), "id");
    }

    /** 에이전트가 MCP write_context 로 남기는 기록. taskId 가 null 이면 작업 없이 남긴다 */
    private ContextRecordResponse write(CallerContext agent, Long taskId, ContextRecordKind kind, Visibility visibility,
                                        String summary) {
        VersionRefDto ref = new VersionRefDto(String.valueOf(api.designItemId("API-01")),
                String.valueOf(api.currentVersionId("API-01")));
        String task = null;
        if (taskId != null) {
            task = String.valueOf(taskId);
        }
        return mcp.writeContext(agent, projectId, new WriteContextArgs(null, task, null, null, kind, visibility, summary,
                "상세 " + summary, List.of(ref), "k-" + summary));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpResponse<String> response) {
        return json.readValue(response.body(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(HttpResponse<String> response) {
        return (List<Map<String, Object>>) body(response).get("items");
    }

    private List<Object> ids(HttpResponse<String> response) {
        List<Object> out = new java.util.ArrayList<>();
        for (Map<String, Object> item : items(response)) {
            out.add(item.get("id"));
        }
        return out;
    }

    private Integer accessLogCount() {
        return api.jdbc.sql("select count(*) from context_access_log").query(Integer.class).single();
    }

    // ---- CTX-A02 목록 ----

    @Test
    void list_byTask_showsAgentSharedRecords_toOtherMembers_withSpecFields() {
        long task = createTask("게스트 로그인", member);
        long otherTask = createTask("방 생성", member);
        ContextRecordResponse shared = write(memberAgent, task, ContextRecordKind.DECISION, Visibility.SHARED, "태그 충돌 시 5회 재시도");
        write(memberAgent, otherTask, ContextRecordKind.PROGRESS, Visibility.SHARED, "다른 작업 기록");

        // 웹 '작업 기록' 카드가 보내는 모양: 검색어 없이 task_id 와 limit 만. 작성자가 아닌 팀장도 본다
        HttpResponse<String> response = api.get(base() + "?task_id=" + task + "&limit=100", leader);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(ids(response)).containsExactly(shared.id());
        Map<String, Object> item = items(response).get(0);
        assertThat(item.get("root_id")).isEqualTo(shared.id());
        assertThat(item.get("revision_no")).isEqualTo(1);
        assertThat(item.get("task_id")).isEqualTo(String.valueOf(task));
        assertThat(item.get("kind")).isEqualTo("DECISION");
        assertThat(item.get("summary")).isEqualTo("태그 충돌 시 5회 재시도");
        assertThat(item.get("author_account_id")).isEqualTo(String.valueOf(member));
        assertThat(item.get("visibility")).isEqualTo("SHARED");
        assertThat(item.get("is_latest_revision")).isEqualTo(true);
        assertThat(item.get("references_current")).isEqualTo(true);
        assertThat(item.get("created_at")).isNotNull();
        // 목록은 요약만 준다 — 상세 내용은 CTX-A03 에서만
        assertThat(item).doesNotContainKey("detail");
        assertThat(body(response).get("has_more")).isEqualTo(false);
        assertThat(body(response)).doesNotContainKey("next_cursor");
    }

    @Test
    void list_privateRecord_isVisibleOnlyToItsAuthor_andNotCounted() {
        ContextRecordResponse shared = write(leaderAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "팀장 공유");
        ContextRecordResponse hidden = write(leaderAgent, null, ContextRecordKind.DECISION, Visibility.PRIVATE, "팀장 비공개");

        // 다른 팀원: 남의 나만 보기 기록은 목록에도 건수에도 없다
        assertThat(ids(api.get(base(), member))).containsExactly(shared.id());
        assertThat(ids(api.get(base() + "?q=" + enc("팀장"), member))).containsExactly(shared.id());
        // 작성자 본인(사람 세션): 에이전트로 남긴 자기 기록이 둘 다 보인다. 최신순
        assertThat(ids(api.get(base(), leader))).containsExactly(hidden.id(), shared.id());
    }

    @Test
    void list_filtersByQueryAndKind_andPagesByCursor() {
        ContextRecordResponse first = write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "로그인 판단 1");
        ContextRecordResponse second = write(memberAgent, null, ContextRecordKind.ISSUE, Visibility.SHARED, "로그인 문제");
        ContextRecordResponse third = write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "로그인 판단 2");
        write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "방 생성 판단");

        // 검색어: 요약에 들어간 것만, 대소문자 구분 없이 최신순
        assertThat(ids(api.get(base() + "?q=" + enc("로그인"), member)))
                .containsExactly(third.id(), second.id(), first.id());
        // 종류
        assertThat(ids(api.get(base() + "?q=" + enc("로그인") + "&kind=ISSUE", member))).containsExactly(second.id());
        // 빈 검색어는 검색어 없음과 같다
        assertThat(items(api.get(base() + "?q=", member))).hasSize(4);

        // 필터를 건 뒤에 페이지를 자른다
        HttpResponse<String> page1 = api.get(base() + "?q=" + enc("로그인") + "&kind=DECISION&limit=1", member);
        assertThat(ids(page1)).containsExactly(third.id());
        assertThat(body(page1).get("has_more")).isEqualTo(true);
        String cursor = (String) body(page1).get("next_cursor");
        HttpResponse<String> page2 = api.get(base() + "?q=" + enc("로그인") + "&kind=DECISION&limit=1&cursor=" + cursor, member);
        assertThat(ids(page2)).containsExactly(first.id());
        assertThat(body(page2).get("has_more")).isEqualTo(false);
    }

    @Test
    void list_rejectsBadInput_andHidesProjectFromOutsiders() {
        write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "공유");

        assertThat(api.get(base() + "?kind=NOPE", member).statusCode()).isEqualTo(400);
        assertThat(api.get(base() + "?task_id=abc", member).statusCode()).isEqualTo(400);
        assertThat(api.get(base() + "?cursor=abc", member).statusCode()).isEqualTo(400);
        // 프로젝트 멤버가 아니면 프로젝트가 없는 것과 같은 404, 로그인하지 않았으면 401
        assertThat(api.get(base(), outsider).statusCode()).isEqualTo(404);
        assertThat(api.get(base(), null).statusCode()).isEqualTo(401);
        // 목록은 조회 이력을 남기지 않는다 (ContextAccessRecorder 기록 지점)
        api.get(base(), member);
        assertThat(accessLogCount()).isZero();
    }

    @Test
    void list_otherProjectTaskFilter_isEmpty_andOtherProjectRecordsNeverLeak() {
        ContextRecordResponse mine = write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "우리 기록");
        Long other = otherProjectWithLeader();
        api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-1', 'q', 'DRAFT')").param("p", other).update();
        Long otherTask = api.jdbc.sql("select id from task where project_id = :p").param("p", other).query(Long.class).single();
        Long otherRecord = insertRecord(other, leader, "남의 프로젝트 기록");

        // 다른 프로젝트 작업 번호로 걸러도 오류 없이 0건
        assertThat(items(api.get(base() + "?task_id=" + otherTask, member))).isEmpty();
        // 이 프로젝트 목록에는 다른 프로젝트 기록이 없다
        assertThat(ids(api.get(base(), leader))).containsExactly(mine.id());
        // 두 프로젝트 모두의 멤버인 팀장이라도, 기록은 자기 프로젝트 주소로만 열린다
        assertThat(api.get(base() + "/" + otherRecord, leader).statusCode()).isEqualTo(404);
        assertThat(api.get("/api/projects/" + other + "/context-records/" + otherRecord, leader).statusCode()).isEqualTo(200);
        assertThat(api.get("/api/projects/" + other + "/context-records/" + mine.id(), leader).statusCode()).isEqualTo(404);
        // 그 프로젝트 멤버가 아닌 사람에게는 프로젝트째로 없다
        assertThat(api.get("/api/projects/" + other + "/context-records", member).statusCode()).isEqualTo(404);
        assertThat(api.get("/api/projects/" + other + "/context-records/" + otherRecord, member).statusCode()).isEqualTo(404);
    }

    // ---- CTX-A03 상세 ----

    @Test
    void get_returnsDetailAndRefs_andLeavesOneAccessLogRow() {
        long task = createTask("게스트 로그인", member);
        ContextRecordResponse shared = write(memberAgent, task, ContextRecordKind.DECISION, Visibility.SHARED, "태그 충돌 시 5회 재시도");

        HttpResponse<String> response = api.get(base() + "/" + shared.id(), leader);

        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Object> record = body(response);
        assertThat(record.get("id")).isEqualTo(shared.id());
        assertThat(record.get("root_id")).isEqualTo(shared.id());
        assertThat(record.get("task_id")).isEqualTo(String.valueOf(task));
        assertThat(record.get("summary")).isEqualTo("태그 충돌 시 5회 재시도");
        assertThat(record.get("detail")).isEqualTo("상세 태그 충돌 시 5회 재시도");
        assertThat(record.get("author_account_id")).isEqualTo(String.valueOf(member));
        assertThat(record.get("is_latest_revision")).isEqualTo(true);
        assertThat(record.get("references_current")).isEqualTo(true);
        // 없는 값은 칸째 뺀다 (명세 N 필드)
        assertThat(record).doesNotContainKeys("previous_id", "plan_revision_id", "source_packet_id");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> refs = (List<Map<String, Object>>) record.get("design_refs");
        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).get("design_item_id")).isEqualTo(String.valueOf(api.designItemId("API-01")));
        assertThat(refs.get(0).get("design_item_version_id")).isEqualTo(String.valueOf(api.currentVersionId("API-01")));

        // 조회 1건 = 이력 1건. 사람 세션이라 토큰은 비고, API 이름으로 남는다
        Map<String, Object> row = api.jdbc.sql("select * from context_access_log").query().singleRow();
        assertThat(row.get("tool")).isEqualTo("CTX-A03");
        assertThat(row.get("target_kind")).isEqualTo("CONTEXT_RECORD");
        assertThat(String.valueOf(row.get("target_id"))).isEqualTo(shared.id());
        assertThat(((Number) row.get("account_id")).longValue()).isEqualTo(leader);
        assertThat(row.get("agent_token_id")).isNull();
        assertThat(((Number) row.get("task_id")).longValue()).isEqualTo(task);
    }

    @Test
    void get_othersPrivateRecord_looksMissing_andAuthorCanReadIt() {
        ContextRecordResponse hidden = write(leaderAgent, null, ContextRecordKind.ISSUE, Visibility.PRIVATE, "팀장 비공개");

        // 남의 나만 보기 기록은 403 이 아니라 404 — 있다는 것도 알리지 않는다
        HttpResponse<String> denied = api.get(base() + "/" + hidden.id(), member);
        assertThat(denied.statusCode()).isEqualTo(404);
        assertThat(denied.body()).contains("not_found").doesNotContain("팀장 비공개");
        assertThat(accessLogCount()).isZero();   // 거절된 조회는 이력을 남기지 않는다

        HttpResponse<String> own = api.get(base() + "/" + hidden.id(), leader);
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(body(own).get("visibility")).isEqualTo("PRIVATE");
        assertThat(body(own).get("detail")).isEqualTo("상세 팀장 비공개");
    }

    @Test
    void get_missingRecord_outsider_andAnonymous_areRejected() {
        ContextRecordResponse shared = write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "공유");

        assertThat(api.get(base() + "/999999", member).statusCode()).isEqualTo(404);
        assertThat(api.get(base() + "/" + shared.id(), outsider).statusCode()).isEqualTo(404);
        assertThat(api.get(base() + "/" + shared.id(), null).statusCode()).isEqualTo(401);
        assertThat(accessLogCount()).isZero();
    }

    @Test
    void get_revisedRecord_marksOldRevisionAsNotLatest() {
        ContextRecordResponse first = write(memberAgent, null, ContextRecordKind.DECISION, Visibility.SHARED, "처음 판단");
        VersionRefDto ref = new VersionRefDto(String.valueOf(api.designItemId("API-01")),
                String.valueOf(api.currentVersionId("API-01")));
        ContextRecordResponse second = mcp.writeContext(memberAgent, projectId, new WriteContextArgs(first.id(), null, null, null,
                ContextRecordKind.DECISION, Visibility.SHARED, "고친 판단", "상세", List.of(ref), "k-고친 판단"));

        Map<String, Object> old = body(api.get(base() + "/" + first.id(), leader));
        assertThat(old.get("is_latest_revision")).isEqualTo(false);
        Map<String, Object> latest = body(api.get(base() + "/" + second.id(), leader));
        assertThat(latest.get("is_latest_revision")).isEqualTo(true);
        assertThat(latest.get("previous_id")).isEqualTo(first.id());
        assertThat(latest.get("root_id")).isEqualTo(first.id());
        assertThat(latest.get("revision_no")).isEqualTo(2);
        // 목록은 모든 리비전을 주고 최신 여부를 표시한다
        List<Map<String, Object>> listed = items(api.get(base(), leader));
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("id")).isEqualTo(second.id());
        assertThat(listed.get(0).get("is_latest_revision")).isEqualTo(true);
        assertThat(listed.get(1).get("id")).isEqualTo(first.id());
        assertThat(listed.get(1).get("is_latest_revision")).isEqualTo(false);
    }

    // ---- 준비 ----

    private static String enc(String raw) {
        return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 팀장만 멤버인 다른 프로젝트 */
    private Long otherProjectWithLeader() {
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into project_member (project_id, account_id, role) values (:p, :a, 'LEADER')")
                .param("p", other).param("a", leader).update();
        return other;
    }

    private Long insertRecord(Long project, Long author, String summary) {
        return api.jdbc.sql("""
                        insert into context_record (project_id, revision_no, author_account_id, kind, visibility, summary, detail)
                        values (:p, 1, :a, 'DECISION', 'SHARED', :s, '상세') returning id
                        """)
                .param("p", project).param("a", author).param("s", summary).query(Long.class).single();
    }
}
