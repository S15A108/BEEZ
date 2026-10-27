package com.beez.backend.contextaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.contextaccess.service.ContextAccessRecorder;
import com.beez.backend.designchange.dto.DesignChangesQuery;
import com.beez.backend.designchange.service.DesignChangesService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 컨텍스트 조회 이력 (CTX-05·06, S15P31A108-165).
 * 기록: 조회 1건 = 이력 1건, 원문·토큰 값 미기록, 같은 트랜잭션. 조회 API: 팀장만, 필터는 페이지 전에, 다른 프로젝트 필터는 0건.
 */
class ContextAccessLogIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    DesignChangesService designChanges;

    @Autowired
    ContextAccessRecorder recorder;

    @Autowired
    ProjectAccessChecker access;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
    }

    private List<Map<String, Object>> logRows() {
        return api.jdbc.sql("select * from context_access_log order by id").query().listOfRows();
    }

    private long createTask(String title, Long account) throws Exception {
        HttpResponse<String> created = api.post("/api/projects/" + projectId + "/tasks",
                api.planJson(title, api.readsRef("API-01"), "[]"), account);
        assertThat(created.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(created.body(), "id");
    }

    // ---- 기록 ----

    @Test
    void designChangesCall_leavesExactlyOneRow_withBaselineAndCaller() throws Exception {
        long task = createTask("A", member);
        Long baseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single();

        designChanges.getChanges(CallerContext.agent(member, api.agentTokenId()), projectId,
                new DesignChangesQuery(0, String.valueOf(task), null, null));

        List<Map<String, Object>> rows = logRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("project_id")).longValue()).isEqualTo(projectId);
        assertThat(((Number) row.get("account_id")).longValue()).isEqualTo(member);
        assertThat(((Number) row.get("agent_token_id")).longValue()).isEqualTo(api.agentTokenId());
        assertThat(((Number) row.get("task_id")).longValue()).isEqualTo(task);
        assertThat(row.get("tool")).isEqualTo("get_design_changes");
        assertThat(row.get("target_kind")).isEqualTo("BASELINE");
        assertThat(((Number) row.get("baseline_id")).longValue()).isEqualTo(baseline);
        assertThat(row.get("packet_id")).isNull();
    }

    @Test
    void humanCall_recordsAccountWithoutToken_andForeignTaskIdIsNotStored() {
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-1', 'q', 'DRAFT')").param("p", other).update();
        Long otherTask = api.jdbc.sql("select id from task where project_id = :p").param("p", other).query(Long.class).single();

        designChanges.getChanges(CallerContext.human(member), projectId,
                new DesignChangesQuery(0, String.valueOf(otherTask), null, null));

        Map<String, Object> row = logRows().get(0);
        assertThat(((Number) row.get("account_id")).longValue()).isEqualTo(member);
        assertThat(row.get("agent_token_id")).isNull();
        assertThat(row.get("task_id")).isNull();   // 다른 프로젝트 작업 번호는 남기지 않는다 (FK·소속 비노출)
    }

    @Test
    void deniedCall_leavesNoRow() {
        Long outsider = api.accountId("outsider@test");
        assertThatThrownBy(() -> designChanges.getChanges(CallerContext.human(outsider), projectId,
                new DesignChangesQuery(0, null, null, null))).isInstanceOf(com.beez.backend.common.api.ApiException.class);
        assertThat(logRows()).isEmpty();
    }

    @Test
    void recorder_outsideTransaction_isRejected() {
        ProjectActor actor = access.requireMember(CallerContext.human(member), projectId);
        // 조회와 같은 트랜잭션에서만 쓰라는 규칙 — 트랜잭션 없이 부르면 바로 예외
        assertThatThrownBy(() -> recorder.record(actor, null, "x", ContextAccessRecorder.KIND_BASELINE, 1L, null, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void storedRow_hasNoFreeTextColumns() {
        // 테이블에 원문·요청 문장을 담을 칸이 없는지 스키마로 확인한다 (MCP 규약 "원문·토큰 미기록")
        List<String> columns = api.jdbc.sql("""
                        select column_name from information_schema.columns
                         where table_name = 'context_access_log' order by ordinal_position
                        """).query(String.class).list();
        assertThat(columns).containsExactlyInAnyOrder("id", "project_id", "agent_token_id", "task_id", "tool", "target_kind",
                "target_id", "baseline_id", "packet_id", "accessed_at", "created_at", "updated_at", "account_id");
        assertThat(columns).doesNotContain("request", "content", "token_hash", "summary", "detail");
    }

    @Test
    void storedRow_cannotBeUpdated() {
        designChanges.getChanges(CallerContext.human(member), projectId, new DesignChangesQuery(0, null, null, null));
        assertThatThrownBy(() -> api.jdbc.sql("update context_access_log set tool = 'x'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void foreignTaskOrBaselineId_isRejectedByDatabase_evenWithoutServiceCheck() {
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-1', 'q', 'DRAFT')").param("p", other).update();
        Long otherTask = api.jdbc.sql("select id from task where project_id = :p").param("p", other).query(Long.class).single();

        // 서비스를 거치지 않고 직접 넣어도 V5 복합 FK 가 막는다
        assertThatThrownBy(() -> api.jdbc.sql("""
                        insert into context_access_log (project_id, account_id, task_id, tool, target_kind)
                        values (:p, :a, :t, 'x', 'BASELINE')
                        """).param("p", projectId).param("a", member).param("t", otherTask).update())
                .hasMessageContaining("fk_context_access_log_task");
    }

    // ---- 조회 API (CTX-A06) ----

    private String path() {
        return "/api/projects/" + projectId + "/context-access-logs";
    }

    @Test
    void list_isLeaderOnly_newestFirst_withTaskFilterAndCursor() throws Exception {
        long t1 = createTask("A", member);
        long t2 = createTask("B", member);
        designChanges.getChanges(CallerContext.human(member), projectId, new DesignChangesQuery(0, String.valueOf(t1), null, null));
        designChanges.getChanges(CallerContext.human(member), projectId, new DesignChangesQuery(0, String.valueOf(t2), null, null));
        designChanges.getChanges(CallerContext.human(member), projectId, new DesignChangesQuery(0, String.valueOf(t1), null, null));

        assertThat(api.get(path(), member).statusCode()).isEqualTo(403);          // 팀원
        assertThat(api.get(path(), api.accountId("outsider@test")).statusCode()).isEqualTo(404);

        HttpResponse<String> all = api.get(path() + "?limit=2", leader);
        assertThat(all.statusCode()).isEqualTo(200);
        assertThat(all.body()).contains("\"has_more\":true");
        String cursor = TaskApiSupport.stringField(all.body(), "next_cursor");
        HttpResponse<String> rest = api.get(path() + "?limit=2&cursor=" + cursor, leader);
        assertThat(rest.body()).contains("\"has_more\":false");

        HttpResponse<String> byTask = api.get(path() + "?task_id=" + t1, leader);
        assertThat(byTask.statusCode()).isEqualTo(200);
        assertThat(countOf(byTask.body(), "\"tool\":\"get_design_changes\"")).isEqualTo(2);
        assertThat(byTask.body()).doesNotContain("\"task_id\":\"" + t2 + "\"");
    }

    @Test
    void list_withOtherProjectsTaskId_isEmpty_notError() throws Exception {
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-1', 'q', 'DRAFT')").param("p", other).update();
        Long otherTask = api.jdbc.sql("select id from task where project_id = :p").param("p", other).query(Long.class).single();
        designChanges.getChanges(CallerContext.human(member), projectId, new DesignChangesQuery(0, null, null, null));

        HttpResponse<String> res = api.get(path() + "?task_id=" + otherTask, leader);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"items\":[]");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int i = text.indexOf(needle, from);
            if (i < 0) {
                return count;
            }
            count++;
            from = i + needle.length();
        }
    }
}
