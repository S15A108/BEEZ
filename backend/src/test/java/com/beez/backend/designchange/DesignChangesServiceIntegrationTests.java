package com.beez.backend.designchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designchange.dto.ChangedItemResponse;
import com.beez.backend.designchange.dto.DesignChangesQuery;
import com.beez.backend.designchange.dto.DesignChangesResponse;
import com.beez.backend.designchange.dto.PublishedChangeResponse;
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
import org.springframework.http.HttpStatus;

/**
 * MCP-03 get_design_changes 를 서비스 직접 호출로 확인한다 (REST 경로가 명세에 없다).
 * 기준 설계 발행(오서현 -194)이 아직 없어 발행은 SQL 로 흉내 낸다: 새 항목 버전 → baseline + baseline_item → 제안 PUBLISHED.
 */
class DesignChangesServiceIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    DesignChangesService service;

    TaskApiSupport api;
    Long projectId;
    Long leader;
    Long member;
    CallerContext asMember;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = api.accountId("leader@test");
        member = api.accountId("member@test");
        asMember = CallerContext.human(member);
    }

    // ---- 발행 흉내 ----

    /** 항목의 새 버전(APPROVED) 번호를 돌려준다 */
    private Long newVersion(String key, int versionNo) {
        Long item = api.designItemId(key);
        api.jdbc.sql("""
                        insert into design_item_version (design_item_id, version_no, status, title, schema_version, content, content_hash, origin, approved_at)
                        values (:i, :n, 'APPROVED', :t, 1, '{}', :h, 'HUMAN', now())
                        """).param("i", item).param("n", versionNo).param("t", key + " v" + versionNo)
                .param("h", "h-" + key + "-v" + versionNo).update();
        return api.jdbc.sql("select id from design_item_version where design_item_id = :i and version_no = :n")
                .param("i", item).param("n", versionNo).query(Long.class).single();
    }

    private void newItem(String key) {
        api.jdbc.sql("insert into design_item (project_id, key, kind) values (:p, :k, 'API_CONTRACT')")
                .param("p", projectId).param("k", key).update();
    }

    /**
     * 기준 설계 versionNo 발행. items 에 넣은 (항목 키 → 버전 번호) 만 기준에 들어가고, 빠진 항목은 폐기로 본다.
     * baseline 은 UPDATE 금지(ERD 트리거)라 실제 발행 순서대로 한다: 제안(APPROVED) → baseline(제안 번호 포함) → 제안 PUBLISHED.
     * 돌려주는 값은 baseline id.
     */
    private Long publish(int versionNo, Map<String, Long> items) {
        Long base = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        api.jdbc.sql("""
                        insert into change_proposal (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                        values (:p, :k, 'APPROVED', :base, 'HUMAN', :a, '테스트')
                        """).param("p", projectId).param("k", "CP-" + versionNo).param("base", base).param("a", leader).update();
        Long proposal = proposalId(versionNo);
        api.jdbc.sql("""
                        insert into baseline (project_id, version_no, published_by, change_proposal_id)
                        values (:p, :v, :a, :cp)
                        """).param("p", projectId).param("v", versionNo).param("a", leader).param("cp", proposal).update();
        Long baselineId = api.jdbc.sql("select id from baseline where project_id = :p and version_no = :v")
                .param("p", projectId).param("v", versionNo).query(Long.class).single();
        for (Map.Entry<String, Long> e : items.entrySet()) {
            api.jdbc.sql("""
                            insert into baseline_item (baseline_id, project_id, design_item_id, design_item_version_id)
                            values (:b, :p, :i, :ver)
                            """).param("b", baselineId).param("p", projectId)
                    .param("i", api.designItemId(e.getKey())).param("ver", e.getValue()).update();
        }
        api.jdbc.sql("update change_proposal set status = 'PUBLISHED', published_baseline_id = :b where id = :cp")
                .param("b", baselineId).param("cp", proposal).update();
        api.jdbc.sql("update project set current_baseline_id = :b where id = :p").param("b", baselineId).param("p", projectId).update();
        return baselineId;
    }

    private Long proposalId(int versionNo) {
        return api.jdbc.sql("select id from change_proposal where key = :k and project_id = :p")
                .param("k", "CP-" + versionNo).param("p", projectId).query(Long.class).single();
    }

    private DesignChangesResponse call(CallerContext caller, int since, String taskId, String cursor, Integer limit) {
        return service.getChanges(caller, projectId, new DesignChangesQuery(since, taskId, cursor, limit));
    }

    private long createTask(String title, String key, Long account) throws Exception {
        HttpResponse<String> created = api.post("/api/projects/" + projectId + "/tasks", api.planJson(title, api.readsRef(key), "[]"), account);
        assertThat(created.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(created.body(), "id");
    }

    private static ChangedItemResponse find(List<ChangedItemResponse> items, String designItemId) {
        for (ChangedItemResponse c : items) {
            if (c.designItemId().equals(designItemId)) {
                return c;
            }
        }
        throw new AssertionError("항목 없음: " + designItemId);
    }

    // ---- 변경 목록 ----

    @Test
    void sinceOne_returnsPublishesAfterIt_withBeforeAndAfterVersions() {
        Long api01v1 = api.currentVersionId("API-01");
        Long api02v1 = api.currentVersionId("API-02");
        // v2: API-01 수정
        Long api01v2 = newVersion("API-01", 2);
        publish(2, Map.of("API-01", api01v2, "API-02", api02v1));
        // v3: API-02 수정, API-03 신규, API-01 폐기
        Long api02v2 = newVersion("API-02", 2);
        newItem("API-03");
        Long api03v1 = newVersion("API-03", 1);
        Long v3 = publish(3, Map.of("API-02", api02v2, "API-03", api03v1));

        DesignChangesResponse res = call(asMember, 1, null, null, null);

        assertThat(res.currentBaseline().versionNo()).isEqualTo(3);
        assertThat(res.currentBaseline().id()).isEqualTo(String.valueOf(v3));
        assertThat(res.hasMore()).isFalse();
        assertThat(res.items()).hasSize(2);

        PublishedChangeResponse third = res.items().get(0);
        assertThat(third.baseline().versionNo()).isEqualTo(3);
        assertThat(third.proposalId()).isEqualTo(String.valueOf(proposalId(3)));
        assertThat(third.changedItems()).hasSize(3);
        ChangedItemResponse retired = find(third.changedItems(), String.valueOf(api.designItemId("API-01")));
        assertThat(retired.beforeVersionId()).isEqualTo(String.valueOf(api01v2));
        assertThat(retired.afterVersionId()).isNull();
        ChangedItemResponse modified = find(third.changedItems(), String.valueOf(api.designItemId("API-02")));
        assertThat(modified.beforeVersionId()).isEqualTo(String.valueOf(api02v1));
        assertThat(modified.afterVersionId()).isEqualTo(String.valueOf(api02v2));
        ChangedItemResponse added = find(third.changedItems(), String.valueOf(api.designItemId("API-03")));
        assertThat(added.beforeVersionId()).isNull();
        assertThat(added.afterVersionId()).isEqualTo(String.valueOf(api03v1));

        PublishedChangeResponse second = res.items().get(1);
        assertThat(second.baseline().versionNo()).isEqualTo(2);
        assertThat(second.changedItems()).hasSize(1);
        assertThat(find(second.changedItems(), String.valueOf(api.designItemId("API-01"))).beforeVersionId())
                .isEqualTo(String.valueOf(api01v1));
    }

    @Test
    void sinceZero_returnsCurrentBaseline_andOnlyLatestChange() {
        Long api02v1 = api.currentVersionId("API-02");
        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api02v1));
        publish(3, Map.of("API-01", newVersion("API-01", 3), "API-02", api02v1));

        DesignChangesResponse res = call(asMember, 0, null, null, null);

        assertThat(res.currentBaseline().versionNo()).isEqualTo(3);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).baseline().versionNo()).isEqualTo(3);
        assertThat(res.hasMore()).isFalse();
    }

    @Test
    void sinceZero_withOnlyFirstBaseline_hasNoChanges() {
        DesignChangesResponse res = call(asMember, 0, null, null, null);
        assertThat(res.currentBaseline().versionNo()).isEqualTo(1);
        assertThat(res.items()).isEmpty();
    }

    @Test
    void invalidSince_is400InvalidVersion() {
        assertDenied(() -> call(asMember, -1, null, null, null), HttpStatus.BAD_REQUEST, "invalid_version");
        assertDenied(() -> call(asMember, 2, null, null, null), HttpStatus.BAD_REQUEST, "invalid_version");
    }

    @Test
    void pendingProposal_isNotListedAsChange() {
        Long base = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single();
        api.jdbc.sql("""
                        insert into change_proposal (project_id, key, status, base_baseline_id, proposer_kind, proposer_account_id, reason)
                        values (:p, 'CP-PENDING', 'PROPOSED', :base, 'HUMAN', :a, '아직 승인 전')
                        """).param("p", projectId).param("base", base).param("a", leader).update();

        DesignChangesResponse res = call(asMember, 0, null, null, null);
        assertThat(res.items()).isEmpty();
        assertThat(res.currentBaseline().versionNo()).isEqualTo(1);
    }

    @Test
    void noBaselineYet_is409BaselineMissing() {
        api.jdbc.sql("update project set current_baseline_id = null where id = :p").param("p", projectId).update();
        assertDenied(() -> call(asMember, 0, null, null, null), HttpStatus.CONFLICT, "baseline_missing");
    }

    // ---- 영향 작업·검증 유효성 ----

    @Test
    void affectedTasks_areThoseWhoseCurrentPlanReadsTheOldVersion_excludingCancelled() throws Exception {
        long readsApi01 = createTask("API-01 읽음", "API-01", member);
        long readsApi02 = createTask("API-02 읽음", "API-02", leader);
        long cancelled = createTask("취소됨", "API-01", member);
        api.jdbc.sql("update task set status = 'CANCELLED', cancelled_at = now() where id = :t").param("t", cancelled).update();

        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));

        DesignChangesResponse res = call(asMember, 1, null, null, null);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).affectedTaskIds()).containsExactly(String.valueOf(readsApi01));
        assertThat(res.items().get(0).affectedTaskIds()).doesNotContain(String.valueOf(readsApi02), String.valueOf(cancelled));
    }

    @Test
    void validationValidity_listsCallersOpenTasks_withStaleReason() throws Exception {
        long mine = createTask("내 작업", "API-01", member);
        long mineWithoutValidation = createTask("검증 없음", "API-02", member);
        long leaders = createTask("팀장 작업", "API-01", leader);
        long staleId = insertValidation(mine, "STALE", "참조 설계 버전이 변경되었습니다.");
        insertValidation(leaders, "PASS", null);

        DesignChangesResponse res = call(asMember, 0, null, null, null);

        assertThat(res.validationValidity()).hasSize(1);
        assertThat(res.validationValidity().get(0).validationId()).isEqualTo(String.valueOf(staleId));
        assertThat(res.validationValidity().get(0).status()).isEqualTo("STALE");
        assertThat(res.validationValidity().get(0).reason()).isEqualTo("참조 설계 버전이 변경되었습니다.");
        assertThat(mineWithoutValidation).isPositive(); // 검증이 없는 작업은 목록에 없다 (위 hasSize(1))
    }

    @Test
    void taskIdScope_limitsChangesAndValidityToThatTask() throws Exception {
        long readsApi01 = createTask("API-01 읽음", "API-01", member);
        long readsApi02 = createTask("API-02 읽음", "API-02", member);
        long passId = insertValidation(readsApi02, "PASS", null);
        insertValidation(readsApi01, "PASS", null);
        Long api02v1 = api.currentVersionId("API-02");
        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api02v1));          // API-01 만 바뀜
        publish(3, Map.of("API-01", api.currentVersionId("API-01"), "API-02", newVersion("API-02", 2))); // API-02 만 바뀜

        DesignChangesResponse res = call(asMember, 1, String.valueOf(readsApi02), null, null);

        // API-02 를 읽는 작업에 영향 준 발행은 v3 뿐
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).baseline().versionNo()).isEqualTo(3);
        assertThat(res.validationValidity()).hasSize(1);
        assertThat(res.validationValidity().get(0).validationId()).isEqualTo(String.valueOf(passId));
    }

    // ---- 리뷰 반영: 번호 건너뜀·폐기 표시·영향 작업 범위 ----

    @Test
    void gapInVersionNumbers_comparesWithNearestLowerBaseline() {
        Long api02v1 = api.currentVersionId("API-02");
        Long api01v2 = newVersion("API-01", 2);
        publish(2, Map.of("API-01", api01v2, "API-02", api02v1));
        Long api01v4 = newVersion("API-01", 4);
        publish(4, Map.of("API-01", api01v4, "API-02", api02v1));   // v3 없음

        DesignChangesResponse res = call(asMember, 2, null, null, null);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).baseline().versionNo()).isEqualTo(4);
        ChangedItemResponse c = find(res.items().get(0).changedItems(), String.valueOf(api.designItemId("API-01")));
        assertThat(c.beforeVersionId()).isEqualTo(String.valueOf(api01v2));
        assertThat(c.afterVersionId()).isEqualTo(String.valueOf(api01v4));
    }

    @Test
    void affectedTasks_excludeCompleted_andIncludeChangesProposer() throws Exception {
        long reads = createTask("읽는 작업", "API-01", member);
        long proposes = createTaskWithRefs("제안한 작업", api.changesRef("API-01"), leader);
        long completed = createTask("완료된 작업", "API-01", member);
        long startedValidation = insertValidation(completed, "PASS", null);
        api.jdbc.sql("update task set status = 'COMPLETED', started_validation_id = :v, completed_at = now() where id = :t")
                .param("v", startedValidation).param("t", completed).update();

        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));

        DesignChangesResponse res = call(asMember, 1, null, null, null);
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).affectedTaskIds())
                .containsExactlyInAnyOrder(String.valueOf(reads), String.valueOf(proposes))
                .doesNotContain(String.valueOf(completed));
    }

    @Test
    void sameItemChangedTwice_bothPublishesAffectTheTaskThatReadsTheOldestVersion() throws Exception {
        long reads = createTask("API-01 v1 읽음", "API-01", member);
        Long api02v1 = api.currentVersionId("API-02");
        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api02v1));
        publish(3, Map.of("API-01", newVersion("API-01", 3), "API-02", api02v1));

        // 작업 지정: v1 을 읽는 계획에는 v1→v2, v2→v3 둘 다 영향
        DesignChangesResponse scoped = call(asMember, 1, String.valueOf(reads), null, null);
        assertThat(scoped.items()).extracting(c -> c.baseline().versionNo()).containsExactly(3, 2);
        // 목록의 영향 작업 칸에도 둘 다 들어간다
        DesignChangesResponse all = call(asMember, 1, null, null, null);
        assertThat(all.items()).allMatch(c -> c.affectedTaskIds().contains(String.valueOf(reads)));
    }

    @Test
    void completedTask_withTaskId_stillListsItsChanges_butNotInAffectedIds() throws Exception {
        long completed = createTask("완료된 작업", "API-01", member);
        long startedValidation = insertValidation(completed, "PASS", null);
        api.jdbc.sql("update task set status = 'COMPLETED', started_validation_id = :v, completed_at = now() where id = :t")
                .param("v", startedValidation).param("t", completed).update();
        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));

        DesignChangesResponse scoped = call(asMember, 1, String.valueOf(completed), null, null);
        assertThat(scoped.items()).hasSize(1);
        assertThat(scoped.items().get(0).affectedTaskIds()).doesNotContain(String.valueOf(completed));
    }

    private long createTaskWithRefs(String title, String refsJson, Long account) throws Exception {
        HttpResponse<String> created = api.post("/api/projects/" + projectId + "/tasks", api.planJson(title, refsJson, "[]"), account);
        assertThat(created.statusCode()).isEqualTo(201);
        return TaskApiSupport.longField(created.body(), "id");
    }

    // ---- 접근 ----

    @Test
    void otherProjectsTaskId_isEmptyResult_notNotFound() throws Exception {
        api.jdbc.sql("insert into project (name, owner_account_id) values ('Q', :a)").param("a", leader).update();
        Long other = api.jdbc.sql("select id from project where name = 'Q'").query(Long.class).single();
        api.jdbc.sql("insert into task (project_id, key, title, status) values (:p, 'T-1', 'q', 'DRAFT')").param("p", other).update();
        Long otherTask = api.jdbc.sql("select id from task where project_id = :p").param("p", other).query(Long.class).single();
        publish(2, Map.of("API-01", newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));

        DesignChangesResponse res = call(asMember, 1, String.valueOf(otherTask), null, null);
        assertThat(res.items()).isEmpty();
        assertThat(res.validationValidity()).isEmpty();
    }

    @Test
    void outsider_is404_andRevokedToken_is401() {
        Long outsider = api.accountId("outsider@test");
        assertDenied(() -> call(CallerContext.human(outsider), 0, null, null, null), HttpStatus.NOT_FOUND, "not_found");

        api.jdbc.sql("update agent_token set revoked_at = now() where id = :t").param("t", api.agentTokenId()).update();
        assertDenied(() -> call(CallerContext.agent(member, api.agentTokenId()), 0, null, null, null),
                HttpStatus.UNAUTHORIZED, "token_revoked");
    }

    // ---- 페이지 ----

    @Test
    void paging_keepsFirstPageBaseline_whenNewPublishHappensBetweenPages() {
        Long api02v1 = api.currentVersionId("API-02");
        for (int v = 2; v <= 5; v++) {
            publish(v, Map.of("API-01", newVersion("API-01", v), "API-02", api02v1));
        }

        DesignChangesResponse first = call(asMember, 1, null, null, 2);
        assertThat(first.currentBaseline().versionNo()).isEqualTo(5);
        assertThat(first.items()).extracting(c -> c.baseline().versionNo()).containsExactly(5, 4);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();

        // 페이지를 넘기는 사이 v6 발행
        publish(6, Map.of("API-01", newVersion("API-01", 6), "API-02", api02v1));

        DesignChangesResponse second = call(asMember, 1, null, first.nextCursor(), 2);
        assertThat(second.currentBaseline().versionNo()).isEqualTo(5);   // 첫 페이지 시점으로 고정
        assertThat(second.items()).extracting(c -> c.baseline().versionNo()).containsExactly(3, 2);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();

        // 다음 호출(since=5)에서 v6 가 나온다
        DesignChangesResponse next = call(asMember, 5, null, null, null);
        assertThat(next.items()).extracting(c -> c.baseline().versionNo()).containsExactly(6);
    }

    @Test
    void sinceZeroCursor_continuesWholeRange_notFirstConnectRule() {
        // report_task_state 는 발행 전에 세운 계획이면 since=0 으로 cursor 를 만든다. 이어 볼 때 "처음 연결(최근 1건)"을 적용하면 안 된다
        Long api02v1 = api.currentVersionId("API-02");
        for (int v = 2; v <= 5; v++) {
            publish(v, Map.of("API-01", newVersion("API-01", v), "API-02", api02v1));
        }
        // 첫 페이지가 v5·v4 였던 since=0 조회의 cursor (ChangesCursor 형식: 버전|ceiling|마지막 번호|since|작업)
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("v1|5|4|0|".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        DesignChangesResponse second = call(asMember, 0, null, cursor, 2);
        assertThat(second.items()).extracting(c -> c.baseline().versionNo()).containsExactly(3, 2);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void cursor_fromDifferentQuery_orMalformed_is400() {
        Long api02v1 = api.currentVersionId("API-02");
        for (int v = 2; v <= 4; v++) {
            publish(v, Map.of("API-01", newVersion("API-01", v), "API-02", api02v1));
        }
        DesignChangesResponse first = call(asMember, 1, null, null, 1);

        assertDenied(() -> call(asMember, 2, null, first.nextCursor(), 1), HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> call(asMember, 1, null, "not-a-cursor", 1), HttpStatus.BAD_REQUEST, "validation_failed");
    }

    // ---- 도우미 ----

    private long insertValidation(long taskId, String status, String staleReason) {
        Long revision = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId).query(Long.class).single();
        Long baselineId = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId).query(Long.class).single();
        api.jdbc.sql("""
                        insert into validation (task_id, plan_revision_id, baseline_id, status, trigger, input_snapshot_hash, rule_version, stale_reason)
                        values (:t, :r, :b, :s, 'AGENT', 'snap-' || :r, 'r1', :reason)
                        """).param("t", taskId).param("r", revision).param("b", baselineId).param("s", status)
                .param("reason", staleReason).update();
        return api.jdbc.sql("select max(id) from validation where task_id = :t").param("t", taskId).query(Long.class).single();
    }

    private static void assertDenied(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatus()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                });
    }
}
