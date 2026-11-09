package com.beez.backend.changeproposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.beez.backend.changeproposal.dto.ProposalDtos.Input;
import com.beez.backend.changeproposal.dto.ProposalDtos.Item;
import com.beez.backend.changeproposal.dto.ProposalDtos.Proposal;
import com.beez.backend.changeproposal.dto.PublicationDtos.ApproveRequest;
import com.beez.backend.changeproposal.dto.PublicationDtos.ImpactResult;
import com.beez.backend.changeproposal.dto.PublicationDtos.Preview;
import com.beez.backend.changeproposal.dto.PublicationDtos.Publication;
import com.beez.backend.changeproposal.repository.PublicationRepository;
import com.beez.backend.changeproposal.service.ImpactApprovalGate;
import com.beez.backend.changeproposal.service.ProposalCommandService;
import com.beez.backend.changeproposal.service.ProposalPublicationService;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.validation.service.PublicationImpactAdapter;
import com.beez.backend.validation.service.ValidationAnalyzer;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 변경 제안 승인(CHG-A09)·미리 보기(CHG-A14)를 실제 검증 영향 어댑터({@link PublicationImpactAdapter}, -163)로 끝까지 태운다 (S15P31A108-240).
 * ProposalPublicationIntegrationTest 는 기록용 구현으로 포트 호출 계약만 보고, PublicationImpactIntegrationTests 는 포트를 직접 부른다.
 * 여기서는 그 사이 접점 — 승인이 검증을 STALE, 작업을 WAITING 으로 바꾸는지, 뒤 단계 실패에 같이 되돌아가는지, 미리 보기와 같은지 — 을 본다.
 * 작업·검증 행은 TaskApiSupport 프로젝트(P)에 만들고, 그 프로젝트에 변경 제안을 올린다. 분석 게이트 판정은 별도 시험이 보므로 신뢰함으로 고정한다.
 */
@Import(ProposalIntegrationTest.Support.class)
class ProposalPublicationImpactIntegrationTest extends IntegrationTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired DataSource dataSource;
    @Autowired ProposalCommandService commands;
    @Autowired ProposalPublicationService publications;

    @MockitoSpyBean PublicationImpactAdapter adapter;
    @MockitoSpyBean PublicationRepository repository;
    @MockitoSpyBean ImpactApprovalGate gate;
    @MockitoBean ValidationAnalyzer analyzer;

    TaskApiSupport api;
    long projectId;
    CallerContext leader;
    Long member;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        leader = CallerContext.human(api.accountId("leader@test"));
        member = api.accountId("member@test");
        decide(ImpactApprovalGate.Kind.TRUSTED, "COMPLETED", true);
    }

    /** 프록시 뒤의 스파이. 스텁은 트랜잭션 프록시를 거치지 않고 대상에 직접 건다 */
    PublicationImpactAdapter adapterTarget() {
        return AopTestUtils.getUltimateTargetObject(adapter);
    }

    // ---- 도우미: 분석 게이트 ----

    void decide(ImpactApprovalGate.Kind kind, String status, boolean current) {
        var decision = new ImpactApprovalGate.Decision(kind, status, current,
                "COMPLETED".equals(status) ? null : "analysis_not_started", 2, false);
        doReturn(decision).when(gate).evaluate(any(), anyLong(), anyLong());
        doReturn(decision).when(gate).peek(any(), anyLong(), anyLong());
    }

    // ---- 도우미: 작업·검증 ----

    private String tasksPath() {
        return "/api/projects/" + projectId + "/tasks";
    }

    /** 작업 생성 → {taskId, revisionId, lockVersion} */
    private long[] createTask(String title, String refsJson) {
        HttpResponse<String> res = api.post(tasksPath(), api.planJson(title, refsJson, "[]"), member);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(201);
        long taskId = TaskApiSupport.longField(res.body(), "id");
        long revisionId = api.jdbc.sql("select current_plan_revision_id from task where id = :t").param("t", taskId)
                .query(Long.class).single();
        return new long[] {taskId, revisionId, TaskApiSupport.longField(res.body(), "lock_version")};
    }

    private HttpResponse<String> state(long[] t, String event, long lock, Long validationId, String extra) {
        StringBuilder sb = new StringBuilder("{\"event\":\"").append(event).append("\",\"plan_revision_id\":\"").append(t[1])
                .append("\",\"expected_lock_version\":").append(lock);
        if (validationId != null) {
            sb.append(",\"validation_id\":\"").append(validationId).append("\"");
        }
        if (extra != null) {
            sb.append(",").append(extra);
        }
        return api.post(tasksPath() + "/" + t[0] + "/state-events", sb.append("}").toString(), member);
    }

    /** PASS 검증(SQL)을 만들고 START → IN_PROGRESS. 돌려주는 값은 START 뒤 lock_version */
    private long start(long[] t) {
        long pass = api.passValidation(t[0]);
        HttpResponse<String> res = state(t, "START", t[2], pass, null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return TaskApiSupport.longField(res.body(), "lock_version");
    }

    private String column(String table, String column, long id) {
        return api.jdbc.sql("select " + column + "::text from " + table + " where id = :id").param("id", id)
                .query(String.class).optional().orElse(null);
    }

    /** 승인 전 시나리오: A 진행 중·B 읽기만 하는 준비 작업·C 무관·D 이미 완료된 작업 (모두 PASS 검증이 있다) */
    record Scenario(long a, long passA, long b, long passB, long c, long passC, long d, long passD) { }

    private Scenario scenario() {
        long[] a = createTask("A", api.implementsRef("API-01"));
        start(a);
        long passA = api.jdbc.sql("select max(id) from validation where task_id = :t").param("t", a[0]).query(Long.class).single();
        long[] b = createTask("B", api.readsRef("API-01"));
        long passB = api.passValidation(b[0]);
        long[] c = createTask("C", api.implementsRef("API-02"));
        long passC = api.passValidation(c[0]);
        long[] d = createTask("D", api.readsRef("API-01"));
        long passD = api.passValidation(d[0]);
        api.jdbc.sql("update task set status = 'COMPLETED', started_validation_id = :v, completed_at = now() where id = :t")
                .param("v", passD).param("t", d[0]).update();
        return new Scenario(a[0], passA, b[0], passB, c[0], passC, d[0], passD);
    }

    /** 승인이 건드릴 수 있는 모든 것의 요약. 롤백·멱등·미리 보기 시험이 전후를 이것으로 비교한다 */
    private List<String> snapshot() {
        List<String> out = new ArrayList<>();
        out.add("project.baseline=" + api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(String.class).single());
        out.add("baselines=" + count("select count(*) from baseline where project_id = :p"));
        out.add("approvals=" + count("select count(*) from approval a join change_proposal c on c.id = a.change_proposal_id where c.project_id = :p"));
        out.add("item_versions=" + count("select count(*) from design_item_version v join design_item i on i.id = v.design_item_id where i.project_id = :p"));
        out.add("jobs=" + count("select count(*) from validation_job j join validation v on v.id = j.validation_id join task t on t.id = v.task_id where t.project_id = :p"));
        out.addAll(api.jdbc.sql("select 'proposal ' || id || ' ' || status from change_proposal where project_id = :p order by id")
                .param("p", projectId).query(String.class).list());
        out.addAll(api.jdbc.sql("""
                select 'validation ' || v.id || ' ' || v.status || ' ' || coalesce(v.stale_reason, '-') || ' ' || coalesce(v.baseline_id::text, '-')
                  from validation v join task t on t.id = v.task_id where t.project_id = :p order by v.id
                """).param("p", projectId).query(String.class).list());
        out.addAll(api.jdbc.sql("""
                select 'task ' || id || ' ' || status || ' ' || coalesce(waiting_reason, '-') || ' ' || lock_version
                  from task where project_id = :p order by id
                """).param("p", projectId).query(String.class).list());
        return out;
    }

    private long count(String sql) {
        return api.jdbc.sql(sql).param("p", projectId).query(Long.class).single();
    }

    // ---- 도우미: 변경 제안 ----

    private Item updateApi01() {
        return new Item("UPDATE", "" + api.designItemId("API-01"), "" + api.currentVersionId("API-01"), "API_CONTRACT", 1,
                Map.of("description", "보완", "method", "GET", "path", "/users"));
    }

    private Item retire(String key) {
        return new Item("RETIRE", "" + api.designItemId(key), "" + api.currentVersionId(key), null, null, null);
    }

    private Proposal propose(String key, Item... changes) {
        long baseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();
        return commands.create(leader, projectId, new Input("" + baseline, "변경", "이유", null, null, List.of(changes), List.of(),
                null, null, null), key);
    }

    private Publication approve(Proposal p, String key, String reason) {
        return publications.approve(leader, projectId, Long.parseLong(p.id()),
                new ApproveRequest(p.baseBaselineId(), p.revision(), p.snapshotHash(), reason), key);
    }

    private Preview preview(Proposal p) {
        return publications.preview(leader, projectId, Long.parseLong(p.id()));
    }

    private static Set<String> validationIds(Preview preview) {
        return preview.validationsToStale().stream().map(v -> v.validationId()).collect(Collectors.toSet());
    }

    private static Set<String> taskIds(Preview preview) {
        return preview.tasksToWait().stream().map(t -> t.taskId()).collect(Collectors.toSet());
    }

    private Set<String> staleValidationIds() {
        return Set.copyOf(api.jdbc.sql("""
                select v.id::text from validation v join task t on t.id = v.task_id
                 where t.project_id = :p and v.status = 'STALE'
                """).param("p", projectId).query(String.class).list());
    }

    private Set<String> waitingTaskIds() {
        return Set.copyOf(api.jdbc.sql("select id::text from task where project_id = :p and status = 'WAITING'")
                .param("p", projectId).query(String.class).list());
    }

    // ---- 재검증 알림 (S15P31A108-217): 대기가 된 작업 + STALE 만 된 준비 작업의 담당자, 완료·미배정 제외 ----

    private List<Map<String, Object>> revalidationNotifications() {
        return api.jdbc.sql("select * from notification where project_id = :p and type = 'REVALIDATION_REQUIRED' order by ref_id")
                .param("p", projectId).query().listOfRows();
    }

    @Test
    void approval_notifiesAssigneesOfWaitingAndStaleReadyTasks_notCompletedOrUnrelated_andReplayAddsNothing() {
        Scenario s = scenario();
        Proposal proposal = propose("renotify", updateApi01());

        approve(proposal, "renotify-approve", null);
        approve(proposal, "renotify-approve", null);   // 멱등 재생
        approve(proposal, "renotify-again", null);     // 이미 발행된 제안 재요청

        // A: 진행 중 → 재검증 대기, B: 검증만 STALE 이 된 준비 작업. C(무관)·D(완료)는 대상이 아니다
        var rows = revalidationNotifications();
        assertThat(rows).extracting(r -> r.get("ref_id")).containsExactly(s.a(), s.b());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("recipient_account_id")).isEqualTo(member);
            assertThat(r.get("ref_kind")).isEqualTo("TASK");
        });
        assertThat(rows.getFirst().get("dedupe_key"))
                .isEqualTo("task:" + s.a() + ":revalidation:" + proposal.id() + ":to:" + member);
    }

    @Test
    void approval_skipsTasksWithoutAssignee() {
        Scenario s = scenario();
        api.jdbc.sql("update task set assignee_member_id = null where id = :t").param("t", s.b()).update();

        approve(propose("renotify-unassigned", updateApi01()), "renotify-unassigned-approve", null);

        assertThat(revalidationNotifications()).extracting(r -> r.get("ref_id")).containsExactly(s.a());
    }

    // ---- T1: 승인 → STALE · WAITING ----

    @Test
    void updateApproval_stalesValidationsOfOldVersion_andHoldsOnlyInProgressTasks() {
        Scenario s = scenario();
        long previousBaseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();

        Publication published = approve(propose("t1", updateApi01()), "t1-approve", null);

        long newBaseline = Long.parseLong(published.baseline().id());
        // 실제 어댑터가 승인 트랜잭션 안에서 새 기준·이전 기준·바뀐 항목으로 불렸다
        verify(adapter, times(1)).applyPublication(any(), eq(projectId), argThat(r -> r.baselineId() == newBaseline
                && r.previousBaselineId() == previousBaseline && r.changedItems().size() == 1
                && "UPDATE".equals(r.changedItems().getFirst().operation())));
        // 옛 버전을 참조한 검증은 진행 중·준비·완료된 작업을 가리지 않고 STALE(DESIGN_PUBLISHED)
        for (long v : List.of(s.passA(), s.passB(), s.passD())) {
            assertThat(column("validation", "status", v)).isEqualTo("STALE");
            assertThat(column("validation", "stale_reason", v)).isEqualTo(PublicationImpactAdapter.STALE_REASON);
        }
        assertThat(column("validation", "status", s.passC())).isEqualTo("PASS");              // 무관한 항목
        // 작업은 현재 계획이 참조하는 IN_PROGRESS 만 WAITING(REVALIDATION). READY·COMPLETED 는 그대로
        assertThat(column("task", "status", s.a())).isEqualTo("WAITING");
        assertThat(column("task", "waiting_reason", s.a())).isEqualTo("REVALIDATION");
        assertThat(column("task", "status", s.b())).isEqualTo("READY");
        assertThat(column("task", "status", s.c())).isEqualTo("READY");
        assertThat(column("task", "status", s.d())).isEqualTo("COMPLETED");
        assertThat(column("project", "current_baseline_id", projectId)).isEqualTo(String.valueOf(newBaseline));
    }

    @Test
    void retireApproval_stalesValidationsOfRetiredItem_andHoldsItsInProgressTask() {
        long[] e = createTask("E", api.implementsRef("API-02"));
        start(e);
        long passE = api.jdbc.sql("select max(id) from validation where task_id = :t").param("t", e[0]).query(Long.class).single();
        long[] other = createTask("F", api.implementsRef("API-01"));
        long passOther = api.passValidation(other[0]);

        approve(propose("retire", retire("API-02")), "retire-approve", null);

        verify(adapter, times(1)).applyPublication(any(), eq(projectId), argThat(r -> r.changedItems().size() == 1
                && "RETIRE".equals(r.changedItems().getFirst().operation()) && r.changedItems().getFirst().newVersionId() == null));
        assertThat(column("validation", "status", passE)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", passE)).isEqualTo(PublicationImpactAdapter.STALE_REASON);
        assertThat(column("task", "status", e[0])).isEqualTo("WAITING");
        assertThat(column("task", "waiting_reason", e[0])).isEqualTo("REVALIDATION");
        assertThat(column("validation", "status", passOther)).isEqualTo("PASS");
        assertThat(column("task", "status", other[0])).isEqualTo("READY");
    }

    @Test
    void approvalOfOnlyNewItem_changesNoValidationOrTask() {
        scenario();
        List<String> before = snapshot();

        // CREATE 만 있는 제안은 어댑터에 바뀐 항목이 없다 — 기존 계획이 참조할 수 없는 항목이기 때문
        Publication published = approve(propose("create-only", new Item("CREATE", null, null, "POLICY", 1,
                Map.of("description", "신규 정책"))), "create-only-approve", null);

        verify(adapter, times(1)).applyPublication(any(), eq(projectId), argThat(r -> r.changedItems().isEmpty()));
        List<String> after = snapshot();
        List<String> impactRows = after.stream().filter(r -> r.startsWith("validation ") || r.startsWith("task ")).toList();
        // 검증 행의 기준 번호·작업 행은 그대로 (기준 포인터·이력 같은 승인 자체의 변경만 달라진다)
        assertThat(impactRows).isEqualTo(before.stream().filter(r -> r.startsWith("validation ") || r.startsWith("task ")).toList());
        assertThat(published.baseline().versionNo()).isEqualTo(2);
    }

    // ---- T2: 미리 보기 == 승인 ----

    @Test
    void preview_matchesWhatApprovalActuallyChanges_andWritesNothing() {
        Scenario s = scenario();
        Proposal p = propose("t2", updateApi01());
        List<String> before = snapshot();

        Preview preview = preview(p);

        assertThat(snapshot()).as("미리 보기는 읽기 전용").isEqualTo(before);
        assertThat(validationIds(preview)).containsExactlyInAnyOrder(
                "" + s.passA(), "" + s.passB(), "" + s.passD());
        assertThat(taskIds(preview)).containsExactly("" + s.a());
        assertThat(preview.tasksToWait().getFirst().assigneeMemberId()).isEqualTo(String.valueOf(api.memberId("member@test")));

        approve(p, "t2-approve", null);

        // 정지 상태(그 사이 작업·검증이 바뀌지 않음)에서는 미리 보기가 승인이 STALE·WAITING 으로 바꾼 것과 같다
        assertThat(staleValidationIds()).isEqualTo(validationIds(preview));
        assertThat(waitingTaskIds()).isEqualTo(taskIds(preview));
    }

    // ---- T3: 롤백 ----

    @Test
    void impactFailure_rollsBackWholeApproval() {
        scenario();
        Proposal p = propose("t3a", updateApi01());
        List<String> before = snapshot();
        doThrow(new IllegalStateException("impact failure")).when(adapterTarget()).applyPublication(any(), anyLong(), any());

        assertThatThrownBy(() -> approve(p, "t3a-approve", null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("impact failure");

        // 기준·승인 이력·항목 버전·제안 상태·검증·작업 모두 승인 전 그대로
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void failureAfterImpactWasApplied_rollsBackStaleAndWaitingToo() {
        Scenario s = scenario();
        Proposal p = propose("t3b", updateApi01());
        List<String> before = snapshot();
        List<ImpactResult> applied = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            ImpactResult result = (ImpactResult) inv.callRealMethod();
            applied.add(result);
            return result;
        }).when(adapterTarget()).applyPublication(any(), anyLong(), any());
        // 어댑터가 성공한 뒤 단계(제안 상태 전이·승인 이력 기록)에서 한 번만 실패시킨다
        doThrow(new IllegalStateException("publish failure")).doCallRealMethod().when(repository)
                .publish(anyLong(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any());

        assertThatThrownBy(() -> approve(p, "t3b-approve", null)).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("publish failure");

        // 어댑터는 실제로 STALE·WAITING 을 만들었지만(반환값이 비어 있지 않다) 승인이 실패하면서 모두 되돌아갔다
        assertThat(applied).hasSize(1);
        assertThat(applied.getFirst().staleValidations()).hasSize(3);
        assertThat(applied.getFirst().waitingTasks()).hasSize(1);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(column("task", "status", s.a())).isEqualTo("IN_PROGRESS");
        assertThat(column("validation", "status", s.passA())).isEqualTo("PASS");

        // 실패가 흔적을 남기지 않았으므로 같은 제안을 다시 승인하면 정상적으로 반영된다
        approve(p, "t3b-retry", null);
        assertThat(column("task", "status", s.a())).isEqualTo("WAITING");
        assertThat(column("validation", "status", s.passA())).isEqualTo("STALE");
    }

    // ---- T4: 멱등 ----

    @Test
    void replayAndAlreadyPublishedRequests_doNotApplyImpactAgain() {
        scenario();
        Proposal p = propose("t4", updateApi01());
        Publication first = approve(p, "t4-approve", null);
        List<String> afterFirst = snapshot();
        clearInvocations(adapter);

        Publication replay = approve(p, "t4-approve", null);                       // 같은 Idempotency-Key
        Publication alreadyPublished = approve(p, "t4-other-key", null);           // 이미 PUBLISHED 인 제안, 다른 키

        assertThat(replay.baseline().id()).isEqualTo(first.baseline().id());
        assertThat(alreadyPublished.baseline().id()).isEqualTo(first.baseline().id());
        verify(adapter, never()).applyPublication(any(), anyLong(), any());
        assertThat(snapshot()).as("재요청이 영향을 중복 적용하거나 기준을 더 만들지 않는다").isEqualTo(afterFirst);
    }

    // ---- T5: 241 승인 게이트와 순서 ----

    @Test
    void gateRejection_happensBeforeAnyImpactWrite_andOverrideStillAppliesImpact() {
        Scenario s = scenario();
        Proposal p = propose("t5", updateApi01());
        List<String> before = snapshot();

        decide(ImpactApprovalGate.Kind.UNTRUSTED, "NOT_EVALUATED", false);
        assertThatThrownBy(() -> approve(p, "t5-no-reason", null)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("impact_not_confirmed"));
        decide(ImpactApprovalGate.Kind.RUNNING, "RUNNING", true);
        assertThatThrownBy(() -> approve(p, "t5-running", "그래도 승인")).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo("analysis_running"));

        verify(adapter, never()).applyPublication(any(), anyLong(), any());
        assertThat(snapshot()).isEqualTo(before);

        // 사유를 남겨 우회한 승인도 영향 처리는 똑같이 한다
        decide(ImpactApprovalGate.Kind.UNTRUSTED, "NOT_EVALUATED", false);
        approve(p, "t5-override", "분석 서버 장애로 직접 확인함");
        verify(adapter, times(1)).applyPublication(any(), eq(projectId), any());
        assertThat(column("validation", "status", s.passA())).isEqualTo("STALE");
        assertThat(column("task", "status", s.a())).isEqualTo("WAITING");
    }

    // ---- T6: 승인 뒤 API 로 본 과거 검증과 작업 ----

    @Test
    void afterApproval_pastValidationAndTaskAreReadableWithOldBaselineAndStaleState() throws Exception {
        Scenario s = scenario();
        long previousBaseline = api.jdbc.sql("select current_baseline_id from project where id = :p").param("p", projectId)
                .query(Long.class).single();

        approve(propose("t6", updateApi01()), "t6-approve", null);

        HttpResponse<String> validation = api.get("/api/projects/" + projectId + "/validations/" + s.passA(), member);
        assertThat(validation.statusCode()).as(validation.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(validation.body());
        assertThat(body.get("status").asText()).isEqualTo("STALE");
        assertThat(body.get("baseline_id").asText()).isEqualTo(String.valueOf(previousBaseline));  // 옛 기준 기록은 남는다
        // 옛 버전 참조도 그대로다 (validation_design_ref 는 승인으로 지워지지 않는다)
        assertThat(api.jdbc.sql("select count(*) from validation_design_ref where validation_id = :v").param("v", s.passA())
                .query(Integer.class).single()).isEqualTo(1);

        HttpResponse<String> task = api.get(tasksPath() + "/" + s.a(), member);
        assertThat(task.statusCode()).as(task.body()).isEqualTo(200);
        JsonNode taskBody = JSON.readTree(task.body());
        assertThat(taskBody.get("status").asText()).isEqualTo("WAITING");
        assertThat(taskBody.get("waiting_reason").asText()).isEqualTo("REVALIDATION");
        assertThat(taskBody.get("validation_status").asText()).isEqualTo("STALE");
    }

    // ---- T7: DELTA 재접수 ----

    @Test
    void approvalWhileTaskAwaitsDeltaValidation_acceptsNewDeltaAgainstNewBaseline_andRollsBackWithIt() {
        long[] e = createTask("E", api.implementsRef("API-01"));
        long lock = start(e);
        HttpResponse<String> done = state(e, "COMPLETED", lock, null, "\"delta\":[{\"kind\":\"X\",\"text\":\"y\"}]");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(202);
        long oldDelta = TaskApiSupport.longField(done.body(), "delta_validation_id");
        Proposal p = propose("t7", updateApi01());
        List<String> before = snapshot();
        doThrow(new IllegalStateException("publish failure")).doCallRealMethod().when(repository)
                .publish(anyLong(), anyLong(), anyInt(), anyLong(), anyLong(), anyLong(), any());

        // 뒤 단계 실패: 새 DELTA 검증·job 도 함께 사라진다
        assertThatThrownBy(() -> approve(p, "t7-fail", null)).isInstanceOf(RuntimeException.class);
        assertThat(snapshot()).isEqualTo(before);
        assertThat(deltaValidationCount(e[0])).isEqualTo(1);

        Publication published = approve(p, "t7-ok", null);

        long newBaseline = Long.parseLong(published.baseline().id());
        assertThat(column("validation", "status", oldDelta)).isEqualTo("STALE");
        assertThat(column("validation", "stale_reason", oldDelta)).isEqualTo(PublicationImpactAdapter.STALE_REASON);
        assertThat(deltaValidationCount(e[0])).isEqualTo(2);
        Map<String, Object> latest = api.jdbc.sql("""
                select id, status, baseline_id from validation where task_id = :t and trigger = 'DELTA' order by id desc limit 1
                """).param("t", e[0]).query().singleRow();
        assertThat(((Number) latest.get("id")).longValue()).isGreaterThan(oldDelta);
        assertThat(((Number) latest.get("baseline_id")).longValue()).isEqualTo(newBaseline);   // 포인터가 바뀌기 전에 새 기준으로 접수
        assertThat(latest.get("status")).isEqualTo("NEEDS_INPUT");                             // 계획은 아직 v1 참조 → STALE_REF
        // 이미 WAITING(REVALIDATION) 이던 작업은 그대로이고 승인 응답의 대기 목록에도 없다
        assertThat(column("task", "status", e[0])).isEqualTo("WAITING");
        assertThat(column("task", "waiting_reason", e[0])).isEqualTo("REVALIDATION");
        // 규칙으로 끝난 재접수의 검증 완료 활동은 시스템이 남긴다 — 발행한 팀장이 이 작업의 완료를 보고한 것처럼 보이면 안 된다 (-287, PR #202 리뷰)
        Map<String, Object> finished = api.jdbc.sql("""
                select actor_kind, actor_id, result from activity_event where dedupe_key = :k
                """).param("k", "validation:" + latest.get("id") + ":finished:0").query().singleRow();
        assertThat(finished.get("actor_kind")).isEqualTo("SYSTEM");
        assertThat(finished.get("result")).isEqualTo("FAILURE");
    }

    private int deltaValidationCount(long taskId) {
        return api.jdbc.sql("select count(*) from validation where task_id = :t and trigger = 'DELTA'").param("t", taskId)
                .query(Integer.class).single();
    }
}
