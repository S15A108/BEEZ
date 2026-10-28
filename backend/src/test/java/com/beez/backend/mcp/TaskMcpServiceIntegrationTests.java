package com.beez.backend.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designchange.dto.DesignChangesQuery;
import com.beez.backend.designchange.dto.DesignChangesResponse;
import com.beez.backend.designchange.service.DesignChangesService;
import com.beez.backend.mcp.dto.ReportTaskStateArgs;
import com.beez.backend.mcp.dto.ReportTaskStateResult;
import com.beez.backend.mcp.dto.UpsertTaskPlanArgs;
import com.beez.backend.mcp.service.TaskMcpService;
import com.beez.backend.support.IntegrationTestSupport;
import com.beez.backend.task.TaskApiSupport;
import com.beez.backend.task.dto.DesignRefInput;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.entity.PlanRelation;
import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * MCP-10 upsert_task_plan · MCP-12 report_task_state 업무 서비스 (S15P31A108-166).
 * MCP 서버·어댑터(-176·-179)가 아직 없어 서비스를 직접 부른다 — 어댑터가 하는 것과 같은 호출이다.
 */
class TaskMcpServiceIntegrationTests extends IntegrationTestSupport {

    @Autowired
    DataSource dataSource;

    @Autowired
    TaskMcpService mcp;

    @Autowired
    DesignChangesService designChanges;

    TaskApiSupport api;
    Long projectId;
    Long member;
    CallerContext agent;

    @BeforeEach
    void setUp() {
        api = new TaskApiSupport(this::client, dataSource);
        api.resetAndLoadFixture();
        projectId = api.projectId();
        member = api.accountId("member@test");
        agent = CallerContext.agent(member, api.agentTokenId());
    }

    private TaskPlanInput plan(String title, String key) {
        DesignRefInput ref = new DesignRefInput(String.valueOf(api.designItemId(key)),
                String.valueOf(api.currentVersionId(key)), PlanRelation.READS, null);
        return new TaskPlanInput(title, "목표", List.of("a"), List.of(), "방법", List.of("테스트 통과"), List.of(),
                List.of(ref), List.of(), true, null);
    }

    private TaskDetailResponse create(String title) {
        return mcp.upsertTaskPlan(agent, projectId, new UpsertTaskPlanArgs(plan(title, "API-01"), null, null, null, "k-" + title));
    }

    private static void assertDenied(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    // ---- MCP-10 ----

    @Test
    void upsert_withoutTaskId_createsReadyTask_andRecordsAgentToken() {
        TaskDetailResponse task = create("A");

        assertThat(task.status()).isEqualTo(TaskStatus.READY);
        assertThat(task.currentPlanRevision().revisionNo()).isEqualTo(1);
        assertThat(task.currentPlanRevision().agentTokenId()).isEqualTo(String.valueOf(api.agentTokenId()));
    }

    @Test
    void upsert_withTaskId_requiresExpectedNumbers_thenAddsRevision() {
        TaskDetailResponse task = create("A");

        assertDenied(() -> mcp.upsertTaskPlan(agent, projectId,
                new UpsertTaskPlanArgs(plan("A2", "API-02"), task.id(), null, task.lockVersion(), "k2")),
                HttpStatus.BAD_REQUEST, "validation_failed");

        TaskDetailResponse revised = mcp.upsertTaskPlan(agent, projectId,
                new UpsertTaskPlanArgs(plan("A2", "API-02"), task.id(), 1, task.lockVersion(), "k3"));
        assertThat(revised.currentPlanRevision().revisionNo()).isEqualTo(2);

        // 같은 번호로 한 번 더 → 경합
        assertDenied(() -> mcp.upsertTaskPlan(agent, projectId,
                new UpsertTaskPlanArgs(plan("A3", "API-01"), task.id(), 1, revised.lockVersion(), "k4")),
                HttpStatus.CONFLICT, "revision_conflict");
    }

    @Test
    void upsert_checksDtoRules_evenWithoutController_andRequiresIdempotencyKey() {
        TaskPlanInput blankTitle = new TaskPlanInput(" ", "목표", List.of(), List.of(), "방법", List.of(), List.of(),
                List.of(), List.of(), true, null);
        assertDenied(() -> mcp.upsertTaskPlan(agent, projectId, new UpsertTaskPlanArgs(blankTitle, null, null, null, "k")),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> mcp.upsertTaskPlan(agent, projectId, new UpsertTaskPlanArgs(plan("A", "API-01"), null, null, null, " ")),
                HttpStatus.BAD_REQUEST, "validation_failed");
    }

    // ---- MCP-12 ----

    private ReportTaskStateResult report(TaskDetailResponse task, TaskEvent event, Long validationId, WaitingReason reason,
                                         String key) {
        String vid = null;
        if (validationId != null) {
            vid = String.valueOf(validationId);
        }
        return mcp.reportTaskState(agent, projectId, new ReportTaskStateArgs(task.id(), event,
                task.currentPlanRevision().id(), task.lockVersion(), vid, reason, null, null, null, key));
    }

    @Test
    void report_returnsTask_andChangesPublishedAfterThePlan() {
        TaskDetailResponse task = create("A");
        long pass = api.passValidation(Long.parseLong(task.id()));

        ReportTaskStateResult started = report(task, TaskEvent.START, pass, null, "s1");
        assertThat(started.task().status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(started.changes().currentBaseline().versionNo()).isEqualTo(1);
        assertThat(started.changes().items()).isEmpty();        // 계획을 v1 에서 세웠고 그 뒤 발행 없음
        assertThat(started.changesSinceVersion()).isEqualTo(1);

        // 진행 중 API-01 이 v2 로 바뀜
        api.publish(2, Map.of("API-01", api.newVersion("API-01", 2), "API-02", api.currentVersionId("API-02")));

        ReportTaskStateResult waited = report(started.task(), TaskEvent.WAIT, null, WaitingReason.INPUT, "w1");
        assertThat(waited.task().status()).isEqualTo(TaskStatus.WAITING);
        assertThat(waited.changes().currentBaseline().versionNo()).isEqualTo(2);
        assertThat(waited.changes().items()).hasSize(1);
        assertThat(waited.changes().items().get(0).affectedTaskIds()).containsExactly(task.id());

        // 조회 이력이 report_task_state 로 남는다 (-165)
        Integer logs = api.jdbc.sql("select count(*) from context_access_log where tool = 'report_task_state'")
                .query(Integer.class).single();
        assertThat(logs).isEqualTo(2);
    }

    /** 항목 key 를 새로 만들고 v1(APPROVED) 버전 ID 를 돌려준다 */
    private Long newItem(String key) {
        api.jdbc.sql("insert into design_item (project_id, key, kind) values (:p, :k, 'API_CONTRACT')")
                .param("p", projectId).param("k", key).update();
        return api.newVersion(key, 1);
    }

    @Test
    void report_changesBeyondOnePage_continueWithGetDesignChanges_usingReturnedSinceVersion() {
        // 계획이 읽을 항목 22개를 v2 기준에 넣는다
        Map<String, Long> current = new HashMap<>();
        current.put("API-01", api.currentVersionId("API-01"));
        current.put("API-02", api.currentVersionId("API-02"));
        List<String> keys = new ArrayList<>();
        for (int i = 1; i <= 22; i++) {
            String key = String.format("X-%02d", i);
            keys.add(key);
            current.put(key, newItem(key));
        }
        api.publish(2, current);

        // 22개를 모두 읽는 계획 (v2 에서 세움) → 시작
        List<DesignRefInput> refs = new ArrayList<>();
        for (String key : keys) {
            refs.add(new DesignRefInput(String.valueOf(api.designItemId(key)), String.valueOf(current.get(key)),
                    PlanRelation.READS, null));
        }
        TaskPlanInput wide = new TaskPlanInput("넓은 작업", "목표", List.of("a"), List.of(), "방법", List.of("테스트 통과"),
                List.of(), refs, List.of(), true, null);
        TaskDetailResponse task = mcp.upsertTaskPlan(agent, projectId, new UpsertTaskPlanArgs(wide, null, null, null, "k-wide"));
        long pass = api.passValidation(Long.parseLong(task.id()));
        ReportTaskStateResult started = report(task, TaskEvent.START, pass, null, "s1");

        // 항목마다 따로 바뀌는 발행 22번 (v3~v24). 모두 이 작업에 영향
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            current.put(key, api.newVersion(key, 2));
            api.publish(3 + i, new HashMap<>(current));
        }

        ReportTaskStateResult waited = report(started.task(), TaskEvent.WAIT, null, WaitingReason.INPUT, "w1");
        assertThat(waited.changesSinceVersion()).isEqualTo(2);
        assertThat(waited.changes().items()).hasSize(DesignChangesService.DEFAULT_LIMIT);
        assertThat(waited.changes().hasMore()).isTrue();

        // 에이전트: 돌려받은 기준 번호·같은 작업·next_cursor 로 이어 본다
        DesignChangesResponse rest = designChanges.getChanges(agent, projectId, new DesignChangesQuery(
                waited.changesSinceVersion(), task.id(), waited.changes().nextCursor(), null));
        assertThat(rest.items()).extracting(c -> c.baseline().versionNo()).containsExactly(4, 3);
        assertThat(rest.hasMore()).isFalse();
    }

    @Test
    void report_requiresTaskIdAndIdempotencyKey_andKeepsStateRules() {
        TaskDetailResponse task = create("A");
        assertDenied(() -> mcp.reportTaskState(agent, projectId, new ReportTaskStateArgs(null, TaskEvent.START,
                task.currentPlanRevision().id(), task.lockVersion(), null, null, null, null, null, "k")),
                HttpStatus.BAD_REQUEST, "validation_failed");
        assertDenied(() -> report(task, TaskEvent.START, null, null, ""), HttpStatus.BAD_REQUEST, "validation_failed");
        // 규칙은 TSK-A07 그대로: PASS 없는 START 는 400(validation_id 누락)
        assertDenied(() -> report(task, TaskEvent.START, null, null, "k"), HttpStatus.BAD_REQUEST, "validation_failed");
    }
}
