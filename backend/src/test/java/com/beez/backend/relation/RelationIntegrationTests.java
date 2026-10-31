package com.beez.backend.relation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.relation.dto.RelationAction;
import com.beez.backend.relation.dto.RelationChangeRequest;
import com.beez.backend.relation.dto.RelationChangeResult;
import com.beez.backend.relation.dto.RelationCommand;
import com.beez.backend.relation.service.DesignDependencyQueryService;
import com.beez.backend.relation.service.RelationStorageService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 관계 조회 API (S15P31A108-167): 관계 저장 서비스(발행 트랜잭션 안에서 호출), TRC-A01·A02, MON-A03.
 * 진입점(발행·변경 승인)은 아직 없어 저장 서비스는 테스트가 트랜잭션을 열고 직접 부른다.
 */
class RelationIntegrationTests extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    RelationStorageService storage;

    @Autowired
    DesignDependencyQueryService queries;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    Long outsider;
    Long baseline1;
    Long api01;
    Long api02;
    Long pol01;
    CallerContext leaderSession;
    CallerContext memberSession;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        outsider = api.accountId("outsider@test");
        leaderSession = CallerContext.human(leader);
        memberSession = CallerContext.human(member);
        baseline1 = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        api01 = api.designItemId("API-01");
        api02 = api.designItemId("API-02");
        // 정책 항목 POL-01 v1 을 기준 1 에 넣는다 (정책 ← API ← 작업 시나리오)
        pol01 = api.jdbc.sql("insert into design_item (project_id, key, kind) values (:p, 'POL-01', 'POLICY') returning id")
                .param("p", projectId).query(Long.class).single();
        Long pol01v1 = api.jdbc.sql("""
                        insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                        values (:i, 1, 'APPROVED', '토큰 만료 정책', 1, '{}', 'h-pol01-v1', 'HUMAN', now()) returning id
                        """).param("i", pol01).query(Long.class).single();
        api.jdbc.sql("insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id) values (:b, :p, :i, :v)")
                .param("b", baseline1).param("p", projectId).param("i", pol01).param("v", pol01v1).update();
    }

    // ---- 도우미 ----

    private RelationChangeResult apply(CallerContext caller, RelationChangeRequest request) {
        // TransactionTemplate: 발행 트랜잭션처럼 트랜잭션을 열고 그 안에서 부른다
        return new TransactionTemplate(transactions).execute(tx -> storage.apply(caller, projectId, request));
    }

    private static RelationCommand cmd(RelationAction action, Long from, Long to) {
        return new RelationCommand(action, from, to, "근거 " + action);
    }

    private RelationChangeRequest commands(RelationCommand... commands) {
        return new RelationChangeRequest(baseline1, null, List.of(), null, List.of(commands));
    }

    private static void assertDenied(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    private long revision() {
        return queries.currentRevision(projectId);
    }

    private Integer count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    private JsonNode getJson(String path, Long account) {
        HttpResponse<String> res = api.get(path, account);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    private String dependenciesPath(Long item) {
        return "/api/projects/" + projectId + "/design-items/" + item + "/dependencies";
    }

    private static List<String> pairs(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(n.get("from_design_item_id").asText() + ">" + n.get("to_design_item_id").asText());
        }
        return out;
    }

    // ---- 관계 저장 서비스 ----

    @Test
    void storage_mustRunInsideCallersTransaction() {
        // MANDATORY: 발행 트랜잭션 밖에서 부르면 바로 실패하고 아무것도 저장하지 않는다
        assertThatThrownBy(() -> storage.apply(leaderSession, projectId, commands(cmd(RelationAction.CONFIRM, api01, pol01))))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("select count(*) from design_item_dependency")).isZero();
    }

    @Test
    void confirm_savesRelationAndHistory_andBumpsRevisionOncePerCall() {
        assertThat(revision()).isZero();
        RelationChangeResult result = apply(leaderSession, commands(
                cmd(RelationAction.CONFIRM, api01, pol01), cmd(RelationAction.CONFIRM, api02, pol01)));

        assertThat(result.relationSetRevision()).isEqualTo(1);
        assertThat(result.changes()).hasSize(2);
        assertThat(revision()).isEqualTo(1);
        // 현재 관계: 확정·확인자
        assertThat(count("select count(*) from design_item_dependency where status = 'CONFIRMED' and origin = 'EXPLICIT'"
                + " and confirmed_by = " + leader + " and confirmed_at is not null")).isEqualTo(2);
        // 이력: 유효 기준·양 끝 버전·근거·확인자·같은 리비전
        Map<String, Object> h = api.jdbc.sql("""
                        select * from design_item_dependency_history where from_item_id = :f and to_item_id = :t
                        """).param("f", api01).param("t", pol01).query().singleRow();
        assertThat(h.get("action")).isEqualTo("CONFIRM");
        assertThat(((Number) h.get("baseline_id")).longValue()).isEqualTo(baseline1);
        assertThat(((Number) h.get("from_version_id")).longValue()).isEqualTo(api.currentVersionId("API-01"));
        assertThat(((Number) h.get("to_version_id")).longValue()).isEqualTo(api.currentVersionId("POL-01"));
        assertThat(((Number) h.get("relation_set_revision")).longValue()).isEqualTo(1);
        assertThat(((Number) h.get("actor_account_id")).longValue()).isEqualTo(leader);
        assertThat(h.get("reason")).isEqualTo("근거 CONFIRM");

        // TRC-A01: 양쪽 방향 모두 이 항목이 끝인 관계
        JsonNode body = getJson(dependenciesPath(pol01), member);
        assertThat(body.get("relation_set_revision").asLong()).isEqualTo(1);
        assertThat(pairs(body.get("items"))).containsExactly(api01 + ">" + pol01, api02 + ">" + pol01);
        assertThat(body.get("items").get(0).get("relation").asText()).isEqualTo("DEPENDS_ON");
        assertThat(body.get("items").get(0).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.get("candidates")).isEmpty();
    }

    @Test
    void candidates_areSeparated_andDoNotBumpRevision_untilConfirmed() {
        // 멤버도 후보는 둘 수 있다. 확정 관계 집합은 그대로
        RelationChangeResult added = apply(memberSession, commands(cmd(RelationAction.ADD_CANDIDATE, api02, api01)));
        assertThat(added.relationSetRevision()).isZero();
        assertThat(count("select count(*) from design_item_dependency_history where relation_set_revision is null")).isEqualTo(1);

        JsonNode body = getJson(dependenciesPath(api01), member);
        assertThat(body.get("items")).isEmpty();
        assertThat(pairs(body.get("candidates"))).containsExactly(api02 + ">" + api01);
        assertThat(body.get("candidates").get(0).get("confirmed").asBoolean()).isFalse();
        assertThat(body.get("candidates").get(0).get("reason").asText()).isEqualTo("근거 ADD_CANDIDATE");

        // 멤버는 확정할 수 없다
        assertDenied(() -> apply(memberSession, commands(cmd(RelationAction.CONFIRM, api02, api01))),
                HttpStatus.FORBIDDEN, "forbidden");
        // 팀장이 확정하면 후보에서 빠지고 확정 관계가 되며 리비전이 오른다
        assertThat(apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api02, api01))).relationSetRevision())
                .isEqualTo(1);
        body = getJson(dependenciesPath(api01), member);
        assertThat(pairs(body.get("items"))).containsExactly(api02 + ">" + api01);
        assertThat(body.get("items").get(0).get("origin").asText()).isEqualTo("EXPLICIT");
        assertThat(body.get("candidates")).isEmpty();

        // 거절은 후보만
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.REJECT_CANDIDATE, api02, api01))),
                HttpStatus.BAD_REQUEST, "invalid_transition");

        // 팀장이 해제한 관계는 후보로 되돌릴 수 없다 (AI 가 같은 관계를 계속 다시 올리지 않게). 해제 상태와 확인자는 그대로
        apply(leaderSession, commands(cmd(RelationAction.RELEASE, api02, api01)));
        assertDenied(() -> apply(memberSession, commands(cmd(RelationAction.ADD_CANDIDATE, api02, api01))),
                HttpStatus.BAD_REQUEST, "invalid_transition");
        assertThat(count("select count(*) from design_item_dependency where status = 'RELEASED' and confirmed_by = " + leader))
                .isEqualTo(1);
        // 다시 쓰려면 팀장이 직접 확정한다
        apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api02, api01)));

        // 거절한 후보는 다시 후보가 될 수 있고, 후보에는 확인자·시각이 없다
        Long pol02 = api.jdbc.sql("insert into design_item (project_id, key, kind) values (:p, 'POL-02', 'POLICY') returning id")
                .param("p", projectId).query(Long.class).single();
        apply(memberSession, commands(cmd(RelationAction.ADD_CANDIDATE, api01, pol02)));
        apply(leaderSession, commands(cmd(RelationAction.REJECT_CANDIDATE, api01, pol02)));
        apply(memberSession, commands(cmd(RelationAction.ADD_CANDIDATE, api01, pol02)));
        assertThat(count("select count(*) from design_item_dependency where status = 'CANDIDATE'"
                + " and confirmed_by is null and confirmed_at is null")).isEqualTo(1);
    }

    @Test
    void recheck_marksTouchingConfirmedRelations_thenKeepAndReleaseInTheSameOrNextCall() {
        apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01), cmd(RelationAction.CONFIRM, api02, pol01)));
        assertThat(revision()).isEqualTo(1);

        // 정책이 바뀐 발행: 정책에 걸린 확정 관계를 재확인 필요로 표시하고, 같은 호출에서 API-01 관계는 유지
        RelationChangeResult result = apply(leaderSession, new RelationChangeRequest(baseline1, null, List.of(pol01),
                "정책 변경", List.of(cmd(RelationAction.KEEP, api01, pol01))));
        assertThat(result.relationSetRevision()).isEqualTo(2);   // 한 번 호출 = 한 번만 오른다
        assertThat(result.changes()).extracting(c -> c.action()).containsExactly("MARK_RECHECK", "MARK_RECHECK", "KEEP");
        // 재확인 이력에도 양 끝의 유효 기준 버전이 남는다 (요청에 없던 상대편 API-02 포함)
        Map<String, Object> marked = api.jdbc.sql("""
                        select * from design_item_dependency_history
                         where action = 'MARK_RECHECK' and from_item_id = :f and to_item_id = :t
                        """).param("f", api02).param("t", pol01).query().singleRow();
        assertThat(((Number) marked.get("from_version_id")).longValue()).isEqualTo(api.currentVersionId("API-02"));
        assertThat(((Number) marked.get("to_version_id")).longValue()).isEqualTo(api.currentVersionId("POL-01"));
        assertThat(marked.get("reason")).isEqualTo("정책 변경");

        JsonNode body = getJson(dependenciesPath(pol01), member);
        assertThat(body.get("items").get(0).get("status").asText()).isEqualTo("CONFIRMED");      // API-01 유지
        assertThat(body.get("items").get(1).get("status").asText()).isEqualTo("NEEDS_RECHECK");  // API-02 재확인 필요

        // 해제: 목록에서 빠지고 이력에만 남는다
        apply(leaderSession, commands(cmd(RelationAction.RELEASE, api02, pol01)));
        body = getJson(dependenciesPath(pol01), member);
        assertThat(pairs(body.get("items"))).containsExactly(api01 + ">" + pol01);
        assertThat(body.get("relation_set_revision").asLong()).isEqualTo(3);

        // 재확인 필요가 아닌 관계는 유지할 수 없고, 이미 확정된 관계는 다시 확정할 수 없다
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.KEEP, api01, pol01))),
                HttpStatus.BAD_REQUEST, "invalid_transition");
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01))),
                HttpStatus.BAD_REQUEST, "invalid_transition");
        // 해제한 관계는 다시 확정할 수 있다
        apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api02, pol01)));

        // TRC-A02: 최신순 이력, 커서 페이지
        String history = dependenciesPath(pol01) + "/history";
        JsonNode first = getJson(history + "?limit=4", member);
        assertThat(first.get("items")).hasSize(4);
        assertThat(first.get("has_more").asBoolean()).isTrue();
        assertThat(first.get("items").get(0).get("action").asText()).isEqualTo("CONFIRM");
        assertThat(first.get("items").get(1).get("action").asText()).isEqualTo("RELEASE");
        assertThat(first.get("items").get(1).get("status").asText()).isEqualTo("RELEASED");
        JsonNode second = getJson(history + "?limit=4&cursor=" + first.get("next_cursor").asText(), member);
        assertThat(second.get("items")).hasSize(3);   // 확정 2 + 재확인 2 + 유지 1 + 해제 1 + 재확정 1 = 7
        assertThat(second.get("has_more").asBoolean()).isFalse();
        assertThat(second.get("items").get(2).get("relation_set_revision").asLong()).isEqualTo(1);
    }

    @Test
    void failedCommand_rollsBackTheWholeCall() {
        // 두 번째 명령이 실패하면 첫 번째 확정·리비전도 남지 않는다 (호출자 트랜잭션과 함께 롤백)
        assertDenied(() -> apply(leaderSession, commands(
                        cmd(RelationAction.CONFIRM, api01, pol01), cmd(RelationAction.KEEP, api02, pol01))),
                HttpStatus.BAD_REQUEST, "invalid_transition");
        assertThat(count("select count(*) from design_item_dependency")).isZero();
        assertThat(count("select count(*) from design_item_dependency_history")).isZero();
        assertThat(revision()).isZero();
    }

    @Test
    void storage_rejectsBadInput() {
        // 권한: 사람 팀장만 확정 (팀장 토큰의 에이전트·멤버 403, 밖의 사람 404)
        assertDenied(() -> apply(CallerContext.agent(leader, api.leaderAgentTokenId()),
                commands(cmd(RelationAction.CONFIRM, api01, pol01))), HttpStatus.FORBIDDEN, "forbidden");
        assertDenied(() -> apply(CallerContext.human(outsider), commands(cmd(RelationAction.CONFIRM, api01, pol01))),
                HttpStatus.NOT_FOUND, "not_found");
        // 같은 항목끼리 · 같은 쌍 두 번 · 근거 없음 · 다른 프로젝트(없는) 항목
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, api01))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01),
                cmd(RelationAction.RELEASE, api01, pol01))), HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> apply(leaderSession, commands(new RelationCommand(RelationAction.CONFIRM, api01, pol01, " "))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, 999999L))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        // 근거 2,000자: 글자 수(코드 포인트)로 센다. 이모지 2,000개는 통과, 2,001개는 400
        String emoji = "😀";
        apply(leaderSession, commands(new RelationCommand(RelationAction.CONFIRM, api01, pol01, emoji.repeat(2000))));
        assertDenied(() -> apply(leaderSession, commands(new RelationCommand(RelationAction.CONFIRM, api02, pol01,
                emoji.repeat(2001)))), HttpStatus.BAD_REQUEST, "validation_failed");
        // 확정에는 유효 기준이 필요하고, 그 기준에 없는 항목은 확정할 수 없다
        assertDenied(() -> apply(leaderSession, new RelationChangeRequest(null, null, List.of(), null,
                List.of(cmd(RelationAction.CONFIRM, api02, pol01)))), HttpStatus.BAD_REQUEST, "validation_failed");
        Long draft = api.jdbc.sql("insert into design_item (project_id, key, kind) values (:p, 'POL-02', 'POLICY') returning id")
                .param("p", projectId).query(Long.class).single();
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api02, draft))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        // 다른 프로젝트 기준은 받지 않는다
        assertDenied(() -> apply(leaderSession, new RelationChangeRequest(999999L, null, List.of(), null,
                List.of(cmd(RelationAction.CONFIRM, api02, pol01)))), HttpStatus.BAD_REQUEST, "validation_failed");
        assertThat(count("select count(*) from design_item_dependency")).isEqualTo(1);
    }

    @Test
    void baseline_mustBeTheLatestOne_currentOrJustCreatedByThisPublish() {
        Long api01v1 = api.currentVersionId("API-01");
        Long pol01v1 = api.currentVersionId("POL-01");
        // 발행 중: 새 기준 행은 만들었지만 아직 현재 기준으로 바꾸기 전 → 가장 최근 기준이므로 받는다
        Long baseline2 = api.jdbc.sql("""
                        insert into baseline (project_id, version_no, published_by) values (:p, 2, :a) returning id
                        """).param("p", projectId).param("a", leader).query(Long.class).single();
        for (Map.Entry<Long, Long> e : Map.of(api01, api01v1, pol01, pol01v1).entrySet()) {
            api.jdbc.sql("insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id) values (:b, :p, :i, :v)")
                    .param("b", baseline2).param("p", projectId).param("i", e.getKey()).param("v", e.getValue()).update();
        }
        // 지난 기준(1)으로는 확정할 수 없다 — 오래된 항목 버전을 근거로 남기게 된다
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        apply(leaderSession, new RelationChangeRequest(baseline2, null, List.of(), null,
                List.of(cmd(RelationAction.CONFIRM, api01, pol01))));
        Map<String, Object> h = api.jdbc.sql("select * from design_item_dependency_history").query().singleRow();
        assertThat(((Number) h.get("baseline_id")).longValue()).isEqualTo(baseline2);
    }

    @Test
    void release_doesNotNeedBaseline() {
        apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01)));
        // 해제만 하는 호출은 기준 없이 된다 (기준이 없는 프로젝트에서도 해제할 수 있게). 이력에는 기준·버전이 비어 있다
        apply(leaderSession, new RelationChangeRequest(null, null, List.of(), null,
                List.of(cmd(RelationAction.RELEASE, api01, pol01))));
        Map<String, Object> h = api.jdbc.sql("select * from design_item_dependency_history where action = 'RELEASE'")
                .query().singleRow();
        assertThat(h.get("baseline_id")).isNull();
        assertThat(h.get("from_version_id")).isNull();
        assertThat(revision()).isEqualTo(2);
        // 확정은 여전히 기준이 필요하다
        assertDenied(() -> apply(leaderSession, new RelationChangeRequest(null, null, List.of(), null,
                List.of(cmd(RelationAction.CONFIRM, api01, pol01)))), HttpStatus.BAD_REQUEST, "validation_failed");
    }

    @Test
    void dependencyHistory_isImmutable() {
        apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01)));
        assertThatThrownBy(() -> api.jdbc.sql("update design_item_dependency_history set reason = 'x'").update())
                .hasMessageContaining("append-only");
    }

    // ---- TRC-A01·A02 접근 ----

    @Test
    void dependencyQueries_hideOtherProjectsAndRequireSession() {
        assertThat(api.get(dependenciesPath(api01), outsider).statusCode()).isEqualTo(404);
        assertThat(api.get(dependenciesPath(999999L), member).statusCode()).isEqualTo(404);
        assertThat(api.get(dependenciesPath(api01) + "/history", outsider).statusCode()).isEqualTo(404);
        assertThat(api.get(dependenciesPath(api01), null).statusCode()).isEqualTo(401);
    }

    @Test
    void otherProjectObjects_lookMissing_andCannotBeLinked() throws Exception {
        api.runSql("fixtures/other-project-validation.sql");
        Long qItem = api.designItemId("Q-API-77");
        Long qTask = api.jdbc.sql("select t.id from task t join project p on p.id = t.project_id where p.name = 'Q'")
                .query(Long.class).single();
        Long qBaseline = api.jdbc.sql("select b.id from baseline b join project p on p.id = b.project_id where p.name = 'Q'")
                .query(Long.class).single();

        // 조회: 다른 프로젝트 객체는 없는 번호와 똑같은 404 (code·message 까지 같음)
        HttpResponse<String> missing = api.get(dependenciesPath(999999L), member);
        HttpResponse<String> foreign = api.get(dependenciesPath(qItem), member);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(foreign.body())).isEqualTo(JSON.readTree(missing.body()));
        assertThat(api.get(dependenciesPath(qItem) + "/history", member).statusCode()).isEqualTo(404);
        HttpResponse<String> foreignTask = api.get("/api/projects/" + projectId + "/relations?task_id=" + qTask, member);
        assertThat(foreignTask.statusCode()).isEqualTo(404);
        assertThat(JSON.readTree(foreignTask.body())).isEqualTo(JSON.readTree(missing.body()));
        assertThat(foreignTask.body()).doesNotContain("Q-API-77").doesNotContain("secret");

        // 저장: 다른 프로젝트 항목·기준으로는 관계를 만들 수 없다
        assertDenied(() -> apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, qItem))),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> apply(leaderSession, new RelationChangeRequest(qBaseline, null, List.of(), null,
                List.of(cmd(RelationAction.CONFIRM, api01, pol01)))), HttpStatus.BAD_REQUEST, "validation_failed");
        assertThat(count("select count(*) from design_item_dependency")).isZero();
    }

    // ---- MON-A03 ----

    @Test
    void relations_aroundTaskAndItem() throws Exception {
        String tasks = "/api/projects/" + projectId + "/tasks";
        HttpResponse<String> created = api.post(tasks, api.planJson("로그인 화면", api.readsRef("API-01"), "[]"), member);
        assertThat(created.statusCode()).isEqualTo(201);
        Long taskId = api.jdbc.sql("select id from task where project_id = :p and key = 'T-1'").param("p", projectId)
                .query(Long.class).single();
        long validationId = api.passValidation(taskId);
        apply(leaderSession, commands(cmd(RelationAction.CONFIRM, api01, pol01)));
        apply(memberSession, commands(cmd(RelationAction.ADD_CANDIDATE, api02, api01)));
        String relations = "/api/projects/" + projectId + "/relations";

        // 작업 기준: 작업 → API-01 (계획이 본 버전), 작업 → 검증, 검증 → API-01
        JsonNode byTask = getJson(relations + "?task_id=" + taskId, member);
        List<String> edges = edges(byTask);
        assertThat(edges).contains("TASK:" + taskId + ">DESIGN_ITEM:" + api01 + ":READS:true",
                "TASK:" + taskId + ">VALIDATION:" + validationId + ":VALIDATED_BY:true",
                "VALIDATION:" + validationId + ">DESIGN_ITEM:" + api01 + ":READS:true");
        assertThat(byTask.get("edges").get(0).get("design_item_version_id").asLong()).isEqualTo(api.currentVersionId("API-01"));
        assertThat(labels(byTask)).contains("T-1 로그인 화면", "API-01 GET /users");

        // 항목 기준: 확정 의존(true)·후보(false)와 이 항목을 참조하는 작업
        JsonNode byItem = getJson(relations + "?design_item_id=" + api01, member);
        assertThat(edges(byItem)).containsExactlyInAnyOrder(
                "DESIGN_ITEM:" + api01 + ">DESIGN_ITEM:" + pol01 + ":DEPENDS_ON:true",
                "DESIGN_ITEM:" + api02 + ">DESIGN_ITEM:" + api01 + ":DEPENDS_ON:false",
                "TASK:" + taskId + ">DESIGN_ITEM:" + api01 + ":READS:true");
        assertThat(labels(byItem)).contains("POL-01 토큰 만료 정책");

        // 둘 다 주면 합친다 (같은 간선은 한 번)
        JsonNode both = getJson(relations + "?task_id=" + taskId + "&design_item_id=" + api01, member);
        assertThat(edges(both)).doesNotHaveDuplicates().containsAll(edges(byItem));

        // 기준 없음 400, 형식 오류 400, 다른 프로젝트·없는 작업 404, 밖의 사람 404
        assertThat(api.get(relations, member).statusCode()).isEqualTo(400);
        assertThat(api.get(relations + "?task_id=abc", member).statusCode()).isEqualTo(400);
        assertThat(api.get(relations + "?task_id=999999", member).statusCode()).isEqualTo(404);
        assertThat(api.get(relations + "?design_item_id=999999", member).statusCode()).isEqualTo(404);
        assertThat(api.get(relations + "?task_id=" + taskId, outsider).statusCode()).isEqualTo(404);
    }

    private static List<String> edges(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode e : body.get("edges")) {
            out.add(e.get("from_type").asText() + ":" + e.get("from_id").asText() + ">" + e.get("to_type").asText()
                    + ":" + e.get("to_id").asText() + ":" + e.get("relation").asText() + ":" + e.get("confirmed").asBoolean());
        }
        return out;
    }

    private static List<String> labels(JsonNode body) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : body.get("nodes")) {
            out.add(n.get("label").asText());
        }
        return out;
    }
}
