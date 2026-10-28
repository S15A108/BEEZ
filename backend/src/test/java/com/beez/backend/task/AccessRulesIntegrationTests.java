package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.service.TaskQueryService;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * ERD 소프트 삭제 칸과 -172 접근 거부 기준을 HTTP 로 확인한다.
 * project.archived_at / account.deleted_at / project_member.active / agent_token.revoked_at / design_item.retired_at
 * 에이전트 토큰 규칙은 MCP 어댑터(-176)처럼 서비스를 직접 불러 확인한다 (에이전트는 REST 로 401).
 */
class AccessRulesIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    TaskQueryService queryService;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    long taskId;

    @BeforeEach
    void setUp() throws Exception {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        HttpResponse<String> created = api.post(tasksPath(), api.planJson("A", api.readsRef("API-01"), "[]"), member);
        assertThat(created.statusCode()).isEqualTo(201);
        taskId = TaskApiSupport.longField(created.body(), "id");
    }

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    // ---- project.archived_at ----

    @Test
    void deletedProject_isNotFoundForEveryone_andMessageIsUnified() throws Exception {
        api.jdbc.sql("update project set archived_at = now() where id = :p").param("p", projectId).update();

        HttpResponse<String> byLeader = api.get(tasksPath() + "/" + taskId, leader);
        HttpResponse<String> byMember = api.post(tasksPath(), api.planJson("B", api.readsRef("API-01"), "[]"), member);
        HttpResponse<String> missingTask = api.get("/api/projects/" + (projectId + 999) + "/tasks/1", member);

        assertThat(byLeader.statusCode()).isEqualTo(404);
        assertThat(byMember.statusCode()).isEqualTo(404);
        // 삭제된 프로젝트와 없는 프로젝트가 응답으로 구분되지 않는다
        assertThat(byLeader.body()).isEqualTo(missingTask.body()).contains(AccessErrors.NOT_FOUND_MESSAGE);
    }

    // ---- account.deleted_at ----

    @Test
    void withdrawnAccount_is401() throws Exception {
        api.jdbc.sql("update account set deleted_at = now(), email = null where id = :a").param("a", member).update();

        // 이미 로그인한 세션으로 다시 부른다. 탈퇴로 세션이 무효라 401 (-170 리뷰 반영, 이전에는 404)
        HttpResponse<String> res = api.get(tasksPath() + "/" + taskId, member);
        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ---- project_member.active ----

    @Test
    void removedMember_isNotFound_fromNextRequest() throws Exception {
        assertThat(api.get(tasksPath() + "/" + taskId, member).statusCode()).isEqualTo(200);
        api.jdbc.sql("update project_member set active = false, left_at = now() where account_id = :a").param("a", member).update();

        assertThat(api.get(tasksPath() + "/" + taskId, member).statusCode()).isEqualTo(404);
    }

    @Test
    void assigneeChange_toRemovedMember_isRejected() throws Exception {
        Long memberId = api.memberId("member@test");
        api.jdbc.sql("update project_member set active = false where id = :m").param("m", memberId).update();
        int lock = api.jdbc.sql("select lock_version from task where id = :t").param("t", taskId).query(Integer.class).single();

        HttpResponse<String> res = api.patch(tasksPath() + "/" + taskId + "/assignee",
                "{\"assignee_member_id\": \"" + memberId + "\", \"expected_lock_version\": " + lock + "}", leader);
        assertThat(res.statusCode()).isEqualTo(400);
    }

    // ---- agent_token.revoked_at + 토큰의 계정·프로젝트 ----

    @Test
    void revokedToken_is401() throws Exception {
        CallerContext agent = CallerContext.agent(member, api.agentTokenId());
        assertThat(queryService.getTask(agent, projectId, taskId).id()).isEqualTo(String.valueOf(taskId));

        api.jdbc.sql("update agent_token set revoked_at = now() where id = :t").param("t", api.agentTokenId()).update();
        assertDenied(() -> queryService.getTask(agent, projectId, taskId), HttpStatus.UNAUTHORIZED, "token_revoked");
    }

    @Test
    void someoneElsesToken_is401() throws Exception {
        // leader 계정으로 member 의 토큰을 씀
        CallerContext stolen = CallerContext.agent(leader, api.agentTokenId());
        assertDenied(() -> queryService.getTask(stolen, projectId, taskId), HttpStatus.UNAUTHORIZED, "token_revoked");
    }

    private static void assertDenied(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    // ---- design_item.retired_at ----

    @Test
    void retiredDesignItem_cannotBeReferenced() throws Exception {
        api.jdbc.sql("update design_item set retired_at = now() where key = 'API-02'").update();

        HttpResponse<String> res = api.post(tasksPath(), api.planJson("폐기 참조", api.readsRef("API-02"), "[]"), member);
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("폐기");
    }

    // ---- -172 5번: 다른 프로젝트 근거 패킷은 없는 것과 같은 응답 ----

    @Test
    void contextPacketOfOtherProject_looksMissing() throws Exception {
        // 다른 프로젝트 하나와 그 프로젝트의 패킷을 만든다
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into baseline (project_id, version_no, published_by) values (:p, 1, :a)")
                .param("p", other).param("a", leader).update();
        Long otherBaseline = api.jdbc.sql("select id from baseline where project_id = :p").param("p", other)
                .query(Long.class).single();
        api.jdbc.sql("""
                        insert into dev_context_packet (project_id, request, baseline_id, content, content_hash)
                        values (:p, 'x', :b, '{}', 'h')
                        """).param("p", other).param("b", otherBaseline).update();
        Long otherPacket = api.jdbc.sql("select max(id) from dev_context_packet").query(Long.class).single();

        String plan = api.planJson("남의 패킷", api.readsRef("API-01"), "[]").trim();
        String withPacket = plan.substring(0, plan.lastIndexOf('}')) + ", \"context_packet_id\": \"" + otherPacket + "\"}";
        String withMissing = plan.substring(0, plan.lastIndexOf('}')) + ", \"context_packet_id\": \"999999\"}";

        HttpResponse<String> foreign = api.post(tasksPath(), withPacket, member);
        HttpResponse<String> missing = api.post(tasksPath(), withMissing, member);
        assertThat(foreign.statusCode()).isEqualTo(400);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(foreign.body()).doesNotContain("다른 프로젝트");
    }
}
