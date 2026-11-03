package com.beez.backend.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.beez.backend.support.IntegrationTestSupport;
import java.net.http.HttpResponse;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 검증 입력 보존과 최신성 판정 (S15P31A108-162, AC-13): 계획 변경이 검증 입력을 바꾸지 않으면(제목·접근 방식만) 이전 검증을 이어 쓰고,
 * 바꾸면(완료 기준·계약) 무효화한다. 오래된 검증으로는 시작·재개할 수 없다. PASS 검증 행은 SQL 로 만든다 (TaskStateApiIntegrationTests 와 같음).
 */
class PlanFreshnessIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

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

    // ---- 도우미 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 작업 생성 → {taskId, revisionId, lockVersion} */
    private long[] createTask(String title, String refsJson) throws Exception {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson(title, refsJson, "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        return new long[] {taskId, currentRevision(taskId), TaskApiSupport.longField(res.body(), "lock_version")};
    }

    private long currentRevision(long taskId) {
        return api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId).query(Long.class).single();
    }

    /** 계획 리비전 추가. planJson 에 expected_* 를 붙이고, replace 로 본문을 바꾼다 */
    private HttpResponse<String> revise(long taskId, String title, String refsJson, int expectedRevisionNo, long lock,
                                        Map<String, String> replacements) throws Exception {
        String plan = api.planJson(title, refsJson, "[]").trim();
        for (Map.Entry<String, String> e : replacements.entrySet()) {
            assertThat(plan).as("바꿀 글자가 계획 본문에 있어야 한다: " + e.getKey()).contains(e.getKey());
            plan = plan.replace(e.getKey(), e.getValue());
        }
        String body = plan.substring(0, plan.lastIndexOf('}'))
                + ",\n  \"expected_revision_no\": " + expectedRevisionNo + ",\n  \"expected_lock_version\": " + lock + "\n}";
        return api.post(tasksPath() + "/" + taskId + "/plan-revisions", body, member);
    }

    /** 현재 리비전에 대한 PASS 검증 + 참조 복사 + 규칙 발견 항목 한 건 (워커가 할 일을 SQL 로) */
    private long passValidation(long taskId, long revisionId) {
        Long baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        Long validationId = api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version,
                                                summary, finished_at)
                        values (:t, :r, :b, 'PASS', 'AGENT', 'snap-' || :r, 'r1', '{"rule_version":"r1"}'::jsonb, now())
                        returning id
                        """).param("t", taskId).param("r", revisionId).param("b", baselineId).query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation_design_ref (validation_id, design_item_id, design_item_version_id, relation)
                        select :v, design_item_id, design_item_version_id, relation
                          from task_plan_design_ref where plan_revision_id = :r
                        """).param("v", validationId).param("r", revisionId).update();
        api.jdbc.sql("""
                        insert into validation_finding (validation_id, source, kind, severity, message, resolution)
                        values (:v, 'RULE', 'MISMATCH', 'INFO', '참고', 'FIX_PLAN')
                        """).param("v", validationId).update();
        return validationId;
    }

    private HttpResponse<String> state(long taskId, String event, long revisionId, long lock, Long validationId, String extra)
            throws Exception {
        StringBuilder sb = new StringBuilder("{\"event\":\"").append(event).append("\",\"plan_revision_id\":\"").append(revisionId)
                .append("\",\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":\"").append(validationId).append("\"");
        }
        if (extra != null) {
            sb.append(",").append(extra);
        }
        return api.post(tasksPath() + "/" + taskId + "/state-events", sb.append("}").toString(), member);
    }

    private String column(String table, String column, long id) {
        return api.jdbc.sql("select " + column + "::text from " + table + " where id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    private Integer count(String sql) {
        return api.jdbc.sql(sql).query(Integer.class).single();
    }

    /** 리비전의 가장 최근 검증 번호. 없으면 null */
    private Long latestOf(long revisionId) {
        return api.jdbc.sql("select max(id) from validation where plan_revision_id = :r").param("r", revisionId)
                .query(Long.class).optional().orElse(null);
    }

    // ---- 시험 ----

    @Test
    void titleOnlyChange_keepsValidation_andStartResumeAcceptTheOldNumber() throws Exception {
        long[] t = createTask("A", api.implementsRef("API-01"));
        long v = passValidation(t[0], t[1]);

        // 제목만 바꾼 리비전: 검증 입력이 같다 → 이전 PASS 는 그대로, 새 리비전에 그 검증을 이어 받은 복사본이 생긴다
        HttpResponse<String> renamed = revise(t[0], "A (이름만)", api.implementsRef("API-01"), 1, t[2], Map.of());
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(renamed.body(), "status")).isEqualTo("READY");
        assertThat(TaskApiSupport.stringField(renamed.body(), "validation_status")).isEqualTo("PASS");
        long rev2 = currentRevision(t[0]);
        assertThat(column("validation", "status", v)).isEqualTo("PASS");
        Long copy = latestOf(rev2);
        assertThat(copy).isNotNull().isNotEqualTo(v);
        assertThat(column("validation", "status", copy)).isEqualTo("PASS");
        assertThat(column("validation", "input_snapshot_hash", copy)).isEqualTo(column("validation", "input_snapshot_hash", v));
        assertThat(api.jdbc.sql("select summary->>'carried_from' from validation where id = :v").param("v", copy).query(String.class).single())
                .isEqualTo(String.valueOf(v));
        assertThat(count("select count(*) from validation_design_ref where validation_id = " + copy))
                .isEqualTo(count("select count(*) from validation_design_ref where validation_id = " + v)).isEqualTo(1);
        assertThat(count("select count(*) from validation_finding where validation_id = " + copy)).isEqualTo(1);
        assertThat(count("select count(*) from validation_run where validation_id = " + copy)).isZero();   // 실행은 원본 것

        // 에이전트가 쥔 옛 번호로 START — G1 이 현재 리비전의 복사본으로 판정한다. 시작 검증 번호는 보낸 번호 그대로(멱등 비교 기준)
        long lock = TaskApiSupport.longField(renamed.body(), "lock_version");
        HttpResponse<String> started = state(t[0], "START", rev2, lock, v, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(started.body(), "status")).isEqualTo("IN_PROGRESS");
        assertThat(TaskApiSupport.longField(started.body(), "started_validation_id")).isEqualTo(v);
        HttpResponse<String> again = state(t[0], "START", rev2, lock, v, null);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);                   // 같은 보고 재전송은 멱등

        // 진행 중 제목만 바꿈: WAITING(REVALIDATION) 으로 내리지 않고 검증도 STALE 로 바꾸지 않는다. 복사본의 복사본도 원본 번호를 가리킨다
        long lock2 = TaskApiSupport.longField(started.body(), "lock_version");
        HttpResponse<String> renamedAgain = revise(t[0], "A (또 이름만)", api.implementsRef("API-01"), 2, lock2, Map.of());
        assertThat(renamedAgain.statusCode()).as(renamedAgain.body()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(renamedAgain.body(), "status")).isEqualTo("IN_PROGRESS");
        assertThat(renamedAgain.body()).doesNotContain("REVALIDATION");
        long rev3 = currentRevision(t[0]);
        assertThat(column("validation", "status", v)).isEqualTo("PASS");
        assertThat(column("validation", "status", copy)).isEqualTo("PASS");
        Long copy2 = latestOf(rev3);
        assertThat(api.jdbc.sql("select summary->>'carried_from' from validation where id = :v").param("v", copy2).query(String.class).single())
                .isEqualTo(String.valueOf(v));

        // WAIT 뒤 RESUME 도 옛 번호로 (복사본 사슬의 끝을 본다)
        long lock3 = TaskApiSupport.longField(renamedAgain.body(), "lock_version");
        HttpResponse<String> waited = state(t[0], "WAIT", rev3, lock3, null, "\"waiting_reason\":\"INPUT\"");
        assertThat(waited.statusCode()).as(waited.body()).isEqualTo(200);
        HttpResponse<String> resumed = state(t[0], "RESUME", rev3, TaskApiSupport.longField(waited.body(), "lock_version"), v, null);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        assertThat(TaskApiSupport.stringField(resumed.body(), "status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void criteriaChange_inProgress_invalidates_andOldValidationCannotResume() throws Exception {
        long[] t = createTask("A", api.implementsRef("API-01"));
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = state(t[0], "START", t[1], t[2], v, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);

        // 완료 기준이 바뀜 → 검증 입력이 다르다: PASS 는 STALE(PLAN_CHANGED), 작업은 WAITING(REVALIDATION), 새 리비전엔 검증이 없다
        HttpResponse<String> changed = revise(t[0], "A", api.implementsRef("API-01"), 1, TaskApiSupport.longField(started.body(), "lock_version"),
                Map.of("\"completion_criteria\": [\"테스트 통과\"]", "\"completion_criteria\": [\"테스트 통과\", \"문서 갱신\"]"));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(changed.body(), "status")).isEqualTo("WAITING");
        assertThat(TaskApiSupport.stringField(changed.body(), "waiting_reason")).isEqualTo("REVALIDATION");
        assertThat(TaskApiSupport.stringField(changed.body(), "validation_status")).isEqualTo("NOT_RUN");
        assertThat(column("validation", "status", v)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", v)).isEqualTo("PLAN_CHANGED");
        long rev2 = currentRevision(t[0]);
        assertThat(latestOf(rev2)).isNull();

        // 오래된 검증으로는 재개할 수 없다
        HttpResponse<String> resumed = state(t[0], "RESUME", rev2, TaskApiSupport.longField(changed.body(), "lock_version"), v, null);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(409);
        assertThat(resumed.body()).contains("\"code\":\"validation_not_passed\"");
        assertThat(column("task", "status", t[0])).isEqualTo("WAITING");
    }

    @Test
    void contractChange_ready_invalidatesEvenThoughOnlyProposedContentDiffers() throws Exception {
        long[] t = createTask("A", api.changesRef("API-01"));
        long v = passValidation(t[0], t[1]);
        // 같은 항목·버전·관계인데 제안 내용(계약을 어떻게 바꾸는지)만 다르다 → 다른 입력
        HttpResponse<String> changed = revise(t[0], "A", api.changesRef("API-01"), 1, t[2],
                Map.of("\"memberId\":\"long\"", "\"memberId\":\"string\""));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        long rev2 = currentRevision(t[0]);
        assertThat(latestOf(rev2)).isNull();                                            // 이어 받지 않는다
        assertThat(column("validation", "status", v)).isEqualTo("PASS");                // READY 의 옛 검증은 그대로 두고 G1 이 거른다
        HttpResponse<String> started = state(t[0], "START", rev2, TaskApiSupport.longField(changed.body(), "lock_version"), v, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(409);
        assertThat(started.body()).contains("\"code\":\"validation_not_passed\"").contains("현재 계획");
        assertThat(column("task", "status", t[0])).isEqualTo("READY");
    }

    @Test
    void planChange_whileDeltaValidationPending_isRejected_untilResume() throws Exception {
        long[] t = createTask("A", api.implementsRef("API-01"));
        long v = passValidation(t[0], t[1]);
        HttpResponse<String> started = state(t[0], "START", t[1], t[2], v, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        HttpResponse<String> completed = state(t[0], "COMPLETED", t[1], TaskApiSupport.longField(started.body(), "lock_version"), null,
                "\"summary\":\"끝\",\"delta\":[{\"kind\":\"ADDED_FIELD\",\"text\":\"nickname 추가\"}]");
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(202);
        long delta = TaskApiSupport.longField(completed.body(), "delta_validation_id");
        long lock = TaskApiSupport.longField(completed.body(), "lock_version");

        // delta 검증을 기다리는 동안은 제목만 바꾸는 것도 안 된다 — 완료 보고가 옛 계획에 남아 조용히 사라지는 일을 막는다
        HttpResponse<String> rejected = revise(t[0], "A (이름만)", api.implementsRef("API-01"), 1, lock, Map.of());
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
        assertThat(rejected.body()).contains("\"code\":\"invalid_transition\"").contains(String.valueOf(delta));
        assertThat(count("select count(*) from task_plan_revision where task_id = " + t[0])).isEqualTo(1);
        assertThat(column("validation", "status", delta)).isEqualTo("RUNNING");

        // RESUME 으로 보고를 거두면(DELTA 는 STALE) 바꿀 수 있다
        HttpResponse<String> resumed = state(t[0], "RESUME", t[1], lock, v, null);
        assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
        HttpResponse<String> renamed = revise(t[0], "A (이름만)", api.implementsRef("API-01"), 1,
                TaskApiSupport.longField(resumed.body(), "lock_version"), Map.of());
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(201);
        assertThat(TaskApiSupport.stringField(renamed.body(), "status")).isEqualTo("IN_PROGRESS");
        // 거둔 DELTA(STALE) 가 가장 최근이어도, 그 전의 PASS 를 이어 받는다
        assertThat(TaskApiSupport.stringField(renamed.body(), "validation_status")).isEqualTo("PASS");
    }

    @Test
    void sameContentRevisions_shareValidationInputHash_differentContentDoesNot() throws Exception {
        // READS 만 보는 계획은 규칙만으로 바로 PASS 가 난다 (200) → 해시를 비교할 수 있다
        long[] t = createTask("A", api.readsRef("API-01"));
        HttpResponse<String> first = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + t[1] + "\",\"expected_lock_version\":" + t[2] + "}", member);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        long v1 = TaskApiSupport.longField(first.body(), "id");

        HttpResponse<String> renamed = revise(t[0], "A (이름만)", api.readsRef("API-01"), 1, t[2], Map.of());
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(201);
        long rev2 = currentRevision(t[0]);
        HttpResponse<String> second = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + rev2 + "\",\"expected_lock_version\":" + TaskApiSupport.longField(renamed.body(), "lock_version") + "}",
                member);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
        long v2 = TaskApiSupport.longField(second.body(), "id");
        assertThat(column("validation", "input_snapshot_hash", v2)).isEqualTo(column("validation", "input_snapshot_hash", v1));

        // 검증 접수는 작업의 lock_version 을 바꾸지 않는다 (검증 응답에는 lock_version 이 없다) → 계획 변경 응답의 값을 쓴다
        HttpResponse<String> changed = revise(t[0], "A (이름만)", api.readsRef("API-01"), 2, TaskApiSupport.longField(renamed.body(), "lock_version"),
                Map.of("\"goal\": \"목표\"", "\"goal\": \"다른 목표\""));
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(201);
        long rev3 = currentRevision(t[0]);
        HttpResponse<String> third = api.post(tasksPath() + "/" + t[0] + "/validations",
                "{\"plan_revision_id\":\"" + rev3 + "\",\"expected_lock_version\":" + TaskApiSupport.longField(changed.body(), "lock_version") + "}",
                member);
        assertThat(third.statusCode()).as(third.body()).isEqualTo(200);
        assertThat(column("validation", "input_snapshot_hash", TaskApiSupport.longField(third.body(), "id")))
                .isNotEqualTo(column("validation", "input_snapshot_hash", v1));
    }

    @Test
    void startGate_treatsMissingBaselineItemAsStale_notAsUnchanged() throws Exception {
        long[] t = createTask("A", api.implementsRef("API-01"));
        long v = passValidation(t[0], t[1]);
        // 검증이 본 항목이 현재 기준에서 사라졌다(조회 실패) → "바뀌지 않았다" 가 아니라 낡은 참조로 본다
        api.jdbc.sql("""
                        delete from baseline_item
                         where design_item_id = :i and baseline_id = (select current_baseline_id from project where id = :p)
                        """).param("i", api.designItemId("API-01")).param("p", projectId).update();
        HttpResponse<String> started = state(t[0], "START", t[1], t[2], v, null);
        assertThat(started.statusCode()).as(started.body()).isEqualTo(409);
        assertThat(started.body()).contains("\"code\":\"version_conflict\"");
        assertThat(column("task", "status", t[0])).isEqualTo("READY");
    }
}
