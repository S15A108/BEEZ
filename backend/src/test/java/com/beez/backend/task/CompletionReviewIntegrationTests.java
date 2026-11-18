package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * S15P31A108-269 작업 완료 검증 (작은 범위). 완료 보고의 "실제로 바꾼 설계 항목"을 계획과 규칙으로 대조해
 * 범위 이탈 후보를 보여 준다. 결과는 표시만 하고 완료 전이는 그대로다.
 */
class CompletionReviewIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    ObjectMapper json;

    TaskApiSupport api;
    Long projectId;
    Long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 계획을 만들고 PASS 검증으로 START 까지 한다 → {taskId, revisionId, lockVersion, validationId} */
    private long[] startedTask(String refsJson, boolean noContractChange) throws Exception {
        String plan = api.planJson("A", refsJson, "[]");
        if (!noContractChange) {
            plan = plan.replace("\"no_contract_change\": true", "\"no_contract_change\": false");
        }
        HttpResponse<String> created = api.post(tasksPath(), plan, member);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(created.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        long v = api.passValidation(taskId);
        String start = stateJson("START", revisionId, TaskApiSupport.longField(created.body(), "lock_version"), v, null);
        HttpResponse<String> started = report(taskId, start, member);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        // 진행 중에는 완료 검증 결과가 없다
        assertThat(json.readTree(started.body()).has("completion_review")).isFalse();
        return new long[]{taskId, revisionId, TaskApiSupport.longField(started.body(), "lock_version"), v};
    }

    private String refs(String... entries) {
        return "[" + String.join(",", entries) + "]";
    }

    private String ref(String key, String relation) {
        String content = relation.equals("CHANGES")
                ? ", \"proposed_content\": {\"method\":\"GET\",\"path\":\"/x\",\"response\":{\"memberId\":\"long\"}}"
                : "";
        return "{\"design_item_id\": \"%d\", \"design_item_version_id\": \"%d\", \"relation\": \"%s\"%s}"
                .formatted(api.designItemId(key), api.currentVersionId(key), relation, content);
    }

    /** 계획에 없는 설계 항목 하나 (대조 대상만 되면 되므로 버전은 만들지 않는다) */
    private long extraItem(String key) {
        return api.jdbc.sql("insert into design_item (project_id, key, kind) values (:p, :k, 'API_CONTRACT') returning id")
                .param("p", projectId).param("k", key).query(Long.class).single();
    }

    private String changed(long... ids) {
        List<String> quoted = new ArrayList<>();
        for (long id : ids) {
            quoted.add("\"" + id + "\"");
        }
        return "\"changed_design_item_ids\":[" + String.join(",", quoted) + "]";
    }

    private String stateJson(String event, long revisionId, long lock, Long validationId, String extra) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"event\":\"").append(event).append("\",");
        sb.append("\"plan_revision_id\":\"").append(revisionId).append("\",");
        sb.append("\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":\"").append(validationId).append("\"");
        }
        if (extra != null) {
            sb.append(",").append(extra);
        }
        sb.append("}");
        return sb.toString();
    }

    private HttpResponse<String> report(long taskId, String body, Long account) throws Exception {
        return api.post(tasksPath() + "/" + taskId + "/state-events", body, account);
    }

    private JsonNode detail(long taskId) throws Exception {
        HttpResponse<String> res = api.get(tasksPath() + "/" + taskId, member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            out.add(array.get(i).asString());
        }
        return out;
    }

    private void handover(long taskId, String visibility) {
        api.jdbc.sql("""
                        insert into context_record (project_id, revision_no, task_id, author_account_id, kind, visibility, summary)
                        values (:p, 1, :t, :a, 'HANDOVER', :vis, '인수인계')
                        """).param("p", projectId).param("t", taskId).param("a", member).param("vis", visibility).update();
    }

    private int reportCount(long taskId) {
        return api.jdbc.sql("select count(*) from task_completion_report where task_id = :t").param("t", taskId)
                .query(Integer.class).single();
    }

    // ------------------------------------------------------------------

    @Test
    void outOfPlanChanges_showDeviationCandidates_butTheTaskStillCompletes() throws Exception {
        // 계획: API-01 을 바꾸고 API-02 는 읽기만. 실제로는 API-02 와 계획에 없는 API-09 를 바꿨다고 보고
        long[] t = startedTask(refs(ref("API-01", "CHANGES"), ref("API-02", "READS")), false);
        long api01 = api.designItemId("API-01");
        long api02 = api.designItemId("API-02");
        long api09 = extraItem("API-09");

        String extra = "\"summary\":\"끝\",\"delta\":[]," + changed(api09, api02);
        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], t[2], null, extra), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("status").asString()).isEqualTo("COMPLETED");   // 표시만 — 완료는 막지 않는다

        JsonNode review = body.get("completion_review");
        assertThat(review.get("review_version").asString()).isEqualTo("completion-review-v1");
        assertThat(review.get("changes_reported").asBoolean()).isTrue();
        assertThat(review.get("deviation_count").asInt()).isEqualTo(2);
        JsonNode deviations = review.get("scope_deviations");
        // 정렬된 순서(번호 오름차순)로 나온다: API-02 가 API-09 보다 먼저 만들어졌다
        assertThat(deviations.get(0).get("design_item_id").asString()).isEqualTo(String.valueOf(api02));
        assertThat(deviations.get(0).get("reason").asString()).isEqualTo("NOT_PLANNED_TO_CHANGE");
        assertThat(deviations.get(1).get("design_item_id").asString()).isEqualTo(String.valueOf(api09));
        assertThat(deviations.get(1).get("reason").asString()).isEqualTo("NOT_IN_PLAN");
        // 바꾸기로 한 API-01 은 변경 보고에 없다
        assertThat(strings(review.get("unreported_planned_changes"))).containsExactly(String.valueOf(api01));
        // 계획의 완료 기준 "테스트 통과" 를 확인하지 않았다
        assertThat(strings(review.get("unchecked_criteria"))).containsExactly("테스트 통과");
        assertThat(review.get("handover_recorded").asBoolean()).isFalse();
        assertThat(review.get("notice").asString()).contains("보증하지 않습니다");

        // 보고에는 정렬·중복 제거된 목록으로 저장된다
        String stored = api.jdbc.sql("select changed_design_item_ids::text from task_completion_report where task_id = :t")
                .param("t", t[0]).query(String.class).single();
        assertThat(json.readTree(stored).toString()).isEqualTo("[" + api02 + "," + api09 + "]");

        // 상세 조회에도 같은 결과. 완료 뒤에 인수인계를 남기면 그 경고만 사라진다
        assertThat(detail(t[0]).get("completion_review").get("deviation_count").asInt()).isEqualTo(2);
        handover(t[0], "SHARED");
        JsonNode after = detail(t[0]).get("completion_review");
        assertThat(after.get("handover_recorded").asBoolean()).isTrue();
        assertThat(after.get("deviation_count").asInt()).isEqualTo(2);
    }

    @Test
    void reportThatFollowsThePlan_hasNoDeviations_andCompletes() throws Exception {
        long[] t = startedTask(refs(ref("API-01", "CHANGES"), ref("API-02", "IMPLEMENTS")), false);
        handover(t[0], "SHARED");
        long api01 = api.designItemId("API-01");

        String extra = "\"delta\":[],\"completion_checks\":[{\"criterion\":\"테스트 통과\",\"met\":true}],"
                + changed(api01, api01);   // 중복은 하나로 본다
        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], t[2], null, extra), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode body = json.readTree(res.body());
        assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
        JsonNode review = body.get("completion_review");
        assertThat(review.get("deviation_count").asInt()).isZero();
        assertThat(review.get("scope_deviations").size()).isZero();
        assertThat(review.get("unreported_planned_changes").size()).isZero();
        assertThat(review.get("unmet_criteria").size()).isZero();
        assertThat(review.get("unchecked_criteria").size()).isZero();
        assertThat(review.get("handover_recorded").asBoolean()).isTrue();
    }

    @Test
    void noContractChangeDeclared_makesAnyChangeACandidate_andUnmetCriteriaAreListed() throws Exception {
        // planJson 기본값: API-01 READS, no_contract_change=true
        long[] t = startedTask(api.readsRef("API-01"), true);
        handover(t[0], "PRIVATE");   // 개인 기록은 다른 사람이 못 보니 인수인계로 세지 않는다
        long api01 = api.designItemId("API-01");

        String extra = "\"completion_checks\":[{\"criterion\":\"테스트 통과\",\"met\":false}]," + changed(api01);
        JsonNode review = json.readTree(report(t[0], stateJson("COMPLETED", t[1], t[2], null, extra), member).body())
                .get("completion_review");
        assertThat(review.get("scope_deviations").get(0).get("reason").asString()).isEqualTo("NO_CONTRACT_CHANGE_DECLARED");
        assertThat(strings(review.get("unmet_criteria"))).containsExactly("테스트 통과");
        assertThat(review.get("unchecked_criteria").size()).isZero();
        assertThat(review.get("handover_recorded").asBoolean()).isFalse();
    }

    @Test
    void withoutChangedItems_scopeIsNotJudged() throws Exception {
        long[] t = startedTask(refs(ref("API-01", "CHANGES")), false);
        assertThat(detail(t[0]).has("completion_review")).isFalse();

        HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], t[2], null, "\"delta\":[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode review = json.readTree(res.body()).get("completion_review");
        // 보고하지 않았으면 "이탈 0건" 이 아니라 "판단하지 않음" 이다. 바꾸기로 한 항목 누락도 따지지 않는다
        assertThat(review.get("changes_reported").asBoolean()).isFalse();
        assertThat(review.get("deviation_count").asInt()).isZero();
        assertThat(review.get("unreported_planned_changes").size()).isZero();
        assertThat(api.jdbc.sql("select changed_design_item_ids is null from task_completion_report where task_id = :t")
                .param("t", t[0]).query(Boolean.class).single()).isTrue();
    }

    @Test
    void badChangedItems_are400_andNothingIsSaved() throws Exception {
        long[] t = startedTask(refs(ref("API-01", "CHANGES")), false);
        api.runSql("fixtures/other-project-validation.sql");
        long otherProjects = api.jdbc.sql("select id from design_item where key = 'Q-API-77'").query(Long.class).single();

        for (String bad : List.of(changed(999_999L), changed(otherProjects), "\"changed_design_item_ids\":[\"abc\"]",
                "\"changed_design_item_ids\":[null]")) {
            HttpResponse<String> res = report(t[0], stateJson("COMPLETED", t[1], t[2], null, bad), member);
            assertThat(res.statusCode()).as(bad + " → " + res.body()).isEqualTo(400);
            assertThat(res.body()).contains("\"code\":\"validation_failed\"");
            // 다른 프로젝트 항목 번호가 있다는 사실을 없는 번호와 구분해 알려 주지 않는다
            assertThat(res.body()).doesNotContain("Q-API-77");
        }
        // 완료가 아닌 보고에 보내면 400
        HttpResponse<String> wait = report(t[0], stateJson("WAIT", t[1], t[2], null,
                "\"waiting_reason\":\"INPUT\"," + changed(api.designItemId("API-01"))), member);
        assertThat(wait.statusCode()).as(wait.body()).isEqualTo(400);
        assertThat(wait.body()).contains("\"code\":\"validation_failed\"");

        assertThat(reportCount(t[0])).isZero();
        assertThat(api.jdbc.sql("select status from task where id = :t").param("t", t[0]).query(String.class).single())
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void deltaReport_showsTheReviewWhileWaiting_resendComparesChangedItems_andResumeHidesIt() throws Exception {
        // IMPLEMENTS 참조 → DELTA 검증이 의미 분석 대기로 RUNNING 에 머문다 (테스트에서 워커는 꺼져 있다)
        long[] t = startedTask(refs(ref("API-01", "IMPLEMENTS")), false);
        long api01 = api.designItemId("API-01");
        long api02 = api.designItemId("API-02");

        String delta = "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"nickname 추가\"}],";
        HttpResponse<String> first = report(t[0], stateJson("COMPLETED", t[1], t[2], null, delta + changed(api01, api02)), member);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(202);
        JsonNode firstBody = json.readTree(first.body());
        assertThat(firstBody.get("waiting_reason").asString()).isEqualTo("REVALIDATION");
        assertThat(firstBody.get("completion_review").get("deviation_count").asInt()).isEqualTo(2);
        assertThat(detail(t[0]).get("completion_review").get("deviation_count").asInt()).isEqualTo(2);

        // 같은 항목을 다른 순서로 다시 보냄 → 같은 보고의 재전송(202). 보고·검증은 1건 그대로
        HttpResponse<String> same = report(t[0], stateJson("COMPLETED", t[1], t[2], null, delta + changed(api02, api01)), member);
        assertThat(same.statusCode()).as(same.body()).isEqualTo(202);
        assertThat(json.readTree(same.body()).get("completion_review").get("report_id").asString())
                .isEqualTo(firstBody.get("completion_review").get("report_id").asString());
        // 바꾼 항목이 다르면(또는 보내지 않으면) 다른 보고 → 409
        HttpResponse<String> other = report(t[0], stateJson("COMPLETED", t[1], t[2], null, delta + changed(api01)), member);
        assertThat(other.statusCode()).as(other.body()).isEqualTo(409);
        assertThat(other.body()).contains("\"code\":\"revision_conflict\"");
        HttpResponse<String> omitted = report(t[0], stateJson("COMPLETED", t[1], t[2], null,
                "\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"nickname 추가\"}]"), member);
        assertThat(omitted.statusCode()).as(omitted.body()).isEqualTo(409);
        assertThat(reportCount(t[0])).isEqualTo(1);

        // RESUME 으로 보고를 거두면 옛 보고의 결과를 보여 주지 않는다
        HttpResponse<String> resumed = report(t[0], stateJson("RESUME", t[1], firstBody.get("lock_version").asLong(), t[3], null), member);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(detail(t[0]).has("completion_review")).isFalse();
    }
}
