package com.beez.backend.task.service;

import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.CompletionCheck;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskStateInput;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskCompletionReport;
import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import com.beez.backend.task.event.TaskStateChangedEvent;
import com.beez.backend.task.repository.TaskCompletionReportRepository;
import com.beez.backend.task.repository.TaskRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TSK-A07 작업 상태 보고 (START·RESUME·WAIT·COMPLETED·CANCEL). MCP report_task_state 도 이 메서드를 부른다.
 *
 * <p>처리 순서 (명세 TSK-A07 처리 규칙, 04-task-lifecycle.md §2·§3):
 * <ol>
 *   <li>멤버십 → 권한(담당자 / CANCEL 은 사람 LEADER 도)</li>
 *   <li>project 행 FOR UPDATE — 같은 프로젝트의 전이를 한 줄로 세운다</li>
 *   <li>작업 조회 (잠금 뒤에 읽어야 다른 요청이 방금 바꾼 상태를 본다)</li>
 *   <li>멱등 확인 — lock_version 비교보다 먼저</li>
 *   <li>expected_lock_version · plan_revision_id 비교 → 409 revision_conflict</li>
 *   <li>전이 표 → 금지면 400 invalid_transition</li>
 *   <li>START/RESUME 는 시작 게이트 4조건 → 실패 코드로 409</li>
 *   <li>전이 적용, 커밋 뒤 이벤트</li>
 * </ol>
 */
@Service
public class TaskStateService {

    private final TaskRepository tasks;
    private final TaskCompletionReportRepository reports;
    private final ProjectAccessChecker access;
    private final ProjectDesignLookup designLookup;
    private final TaskTransitionPolicy policy;
    private final StartGate startGate;
    private final TaskQueryService query;
    private final ApplicationEventPublisher events;
    private final JdbcClient jdbc;
    private final ValidationStatusLookup validations;
    private final Clock clock;

    /** 보고가 끊겼다고 보는 시간. 값은 미결(04-task-lifecycle.md §6) — 기본 30분, 설정 beez.task.stalled-after 로 바꾼다 */
    private final Duration stalledAfter;

    public TaskStateService(TaskRepository tasks, TaskCompletionReportRepository reports, ProjectAccessChecker access,
                            ProjectDesignLookup designLookup, TaskTransitionPolicy policy, StartGate startGate,
                            TaskQueryService query, ApplicationEventPublisher events, JdbcClient jdbc,
                            ValidationStatusLookup validations,
                            @Value("${beez.task.stalled-after:PT30M}") Duration stalledAfter) {
        this.tasks = tasks;
        this.reports = reports;
        this.access = access;
        this.designLookup = designLookup;
        this.policy = policy;
        this.startGate = startGate;
        this.query = query;
        this.events = events;
        this.jdbc = jdbc;
        this.validations = validations;
        this.clock = Clock.systemUTC();
        this.stalledAfter = stalledAfter;
    }

    @Transactional
    public TaskDetailResponse report(CallerContext caller, Long projectId, Long taskId, TaskStateInput in) {
        ProjectActor actor = access.requireMember(caller, projectId);
        validateShape(in);
        Long validationId = Ids.parse(in.validationId(), "validation_id");
        Long planRevisionId = Ids.parse(in.planRevisionId(), "plan_revision_id");

        // 2) 프로젝트 잠금. 이 뒤에 읽는 작업 상태가 "지금" 상태다.
        boolean exists = designLookup.lockProject(projectId);
        if (!exists) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        Task task = requireTask(projectId, taskId);
        requirePermission(caller, actor, task, in.event());

        // 4) 멱등 — 같은 보고가 다시 오면 현재 상태 그대로 200. lock_version 비교보다 먼저 (명세 TSK-A07)
        //    START: 현재 IN_PROGRESS 이고 같은 validation_id 로 시작한 경우만 (명세 "기존 START 결과 반환").
        //    RESUME 에는 상태 기반 멱등이 없다 — 시작 때 PASS 로 다시 RESUME 하는 경로(WAIT 후 재개)를 막지 않기 위해.
        //    RESUME 재전송은 Idempotency-Key 가 처리한다 (04-task-lifecycle.md §2.1·§3).
        if (in.event() == TaskEvent.START
                && task.getStatus() == TaskStatus.IN_PROGRESS
                && validationId.equals(task.getStartedValidationId())) {
            return query.toDetail(task);
        }
        //    delta 있는 COMPLETED 재전송: 이미 WAITING(REVALIDATION) 이고 현재 계획의 완료 보고가 있으면 같은 결과(202)를 돌려준다
        if (in.event() == TaskEvent.COMPLETED && isAwaitingDeltaValidation(task)) {
            return query.toDetail(task);
        }
        TaskTransitionPolicy.Verdict verdict = policy.check(task.getStatus(), in.event());
        if (verdict == TaskTransitionPolicy.Verdict.IDEMPOTENT) {
            if (in.event() == TaskEvent.START) {
                // 진행 중인데 다른 검증으로 START 를 또 보냄 — 멱등이 아니라 충돌
                throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                        "이미 다른 검증(" + task.getStartedValidationId() + ")으로 시작된 작업입니다");
            }
            return query.toDetail(task);
        }

        // 5) 충돌 검사 (Integer 끼리는 == 로 비교하면 안 된다 → equals)
        if (!task.getLockVersion().equals(in.expectedLockVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "작업 상태가 이미 바뀌었습니다 (현재 lock_version=" + task.getLockVersion() + ")");
        }
        if (task.getCurrentPlanRevisionId() == null || !task.getCurrentPlanRevisionId().equals(planRevisionId)) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "현재 계획이 아닙니다 (현재 plan_revision_id=" + task.getCurrentPlanRevisionId() + ")");
        }

        // 6) 전이 표
        if (verdict == TaskTransitionPolicy.Verdict.FORBIDDEN) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition",
                    task.getStatus() + " 상태에서는 " + in.event() + " 할 수 없습니다");
        }

        // 7) 시작 게이트
        if (in.event().needsStartGate()) {
            StartGate.Result gate = startGate.evaluate(task, validationId);
            if (!gate.passed()) {
                throw new ApiException(HttpStatus.CONFLICT, gate.first().code(), gate.combinedMessage());
            }
        }

        // 8) 적용
        TaskStatus from = task.getStatus();
        Instant now = Instant.now(clock);
        switch (in.event()) {
            case START -> task.start(validationId, now);
            case RESUME -> task.resume(validationId);
            case WAIT -> task.holdFor(in.waitingReason());
            case COMPLETED -> complete(task, in, caller, now);
            case CANCEL -> cancel(task, now);
        }
        tasks.saveAndFlush(task);

        events.publishEvent(new TaskStateChangedEvent(projectId, taskId, from, task.getStatus(), in.event(),
                caller.accountId(), "task:" + taskId + ":lock:" + task.getLockVersion() + ":" + in.event()));
        return query.toDetail(task);
    }

    // ------------------------------------------------------------------

    /** 이벤트별 필수 값. 상태를 보기 전에 모양만으로 거를 수 있는 것 */
    private void validateShape(TaskStateInput in) {
        if (in.event().needsStartGate() && in.validationId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", in.event() + " 에는 validation_id 가 필요합니다");
        }
        if (in.event() == TaskEvent.WAIT) {
            if (in.waitingReason() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "WAIT 에는 waiting_reason 이 필요합니다");
            }
            if (in.waitingReason().isSystemOnly()) {
                // REVALIDATION 은 시스템만. 에이전트가 사유를 바꿔치기해 재검증 없이 RESUME 하는 길을 막는다
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition", "REVALIDATION 은 시스템 전용 사유입니다");
            }
        }
    }

    /**
     * 담당자 본인만 (04-task-lifecycle.md §4.1).
     * 예외: 사람 세션의 LEADER 는 <b>보고가 끊긴 작업</b>만 CANCEL 할 수 있다 (AC-14, TSK-A07 처리 규칙).
     * 팀장의 에이전트 토큰은 남의 작업을 취소하지 못한다. 그 밖의 팀장 개입은 미결(§6).
     */
    private void requirePermission(CallerContext caller, ProjectActor actor, Task task, TaskEvent event) {
        boolean isAssignee = Long.valueOf(actor.memberId()).equals(task.getAssigneeMemberId());
        if (isAssignee) {
            return;
        }
        if (event == TaskEvent.CANCEL && caller.isHuman() && actor.isLeader()) {
            // 이미 취소된 작업에 대한 재전송은 멱등 처리로 넘긴다
            if (task.getStatus() == TaskStatus.CANCELLED) {
                return;
            }
            if (isStalled(task)) {
                return;
            }
            throw AccessErrors.forbidden("leader_cancel_not_stalled task=" + task.getId(),
                    "팀장은 보고가 끊긴 작업만 취소할 수 있습니다 (마지막 보고 후 " + stalledAfter.toMinutes() + "분)");
        }
        throw AccessErrors.forbidden("not_assignee task=" + task.getId() + " member=" + actor.memberId(),
                "담당자만 작업 상태를 바꿀 수 있습니다");
    }

    /**
     * 보고가 끊긴 작업: <b>IN_PROGRESS</b> 이고 마지막 보고 이후 stalledAfter 가 지났다 (04-task-lifecycle.md §4.1, 담당자 결정).
     * WAITING 은 보고가 멈추는 게 정상이라 넣지 않는다 — 넣으면 팀장 승인을 기다리는 작업까지 팀장이 취소할 수 있게 된다.
     * 마지막 보고 = 에이전트 세션의 last_report_at 과 작업의 마지막 변경 시각 중 늦은 쪽.
     * (greatest 는 Postgres 함수로, NULL 은 무시하고 가장 큰 값을 돌려준다)
     */
    private boolean isStalled(Task task) {
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            return false;
        }
        // Postgres 드라이버는 timestamptz 를 OffsetDateTime 으로 읽는다 (Instant 로 바로는 못 읽음)
        OffsetDateTime lastReport = jdbc.sql("""
                        select greatest(t.updated_at,
                                        (select max(s.last_report_at) from agent_session s where s.current_task_id = t.id))
                          from task t
                         where t.id = :taskId
                        """)
                .param("taskId", task.getId())
                .query(OffsetDateTime.class)
                .single();
        Instant threshold = Instant.now(clock).minus(stalledAfter);
        return lastReport.toInstant().isBefore(threshold);
    }

    /** delta 있는 완료 보고를 이미 받아 DELTA 검증을 기다리는 중인가 (S3 이후 상태) */
    private boolean isAwaitingDeltaValidation(Task task) {
        if (task.getStatus() != TaskStatus.WAITING || task.getWaitingReason() != WaitingReason.REVALIDATION) {
            return false;
        }
        Integer count = jdbc.sql("""
                        select count(*)
                          from task_completion_report
                         where task_id = :taskId
                           and plan_revision_id = :revisionId
                           and has_delta = true
                        """)
                .param("taskId", task.getId())
                .param("revisionId", task.getCurrentPlanRevisionId())
                .query(Integer.class)
                .single();
        return count > 0;
    }

    private void complete(Task task, TaskStateInput in, CallerContext caller, Instant now) {
        List<Map<String, Object>> delta = in.delta();
        if (delta == null) {
            delta = new ArrayList<>();
        }
        // 완료 기준 확인은 형식 검사를 거친 CompletionCheck 로 받고, 저장(JSONB)은 {criterion, met} 로 한다
        List<Map<String, Object>> checks = new ArrayList<>();
        if (in.completionChecks() != null) {
            for (CompletionCheck c : in.completionChecks()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("criterion", c.criterion());
                row.put("met", c.met());
                checks.add(row);
            }
        }
        reports.save(new TaskCompletionReport(task.getId(), task.getCurrentPlanRevisionId(), delta, in.summary(),
                checks, caller.agentTokenId()));
        if (delta.isEmpty()) {
            task.complete(now);
        } else {
            // 계획과 다른 점이 있다 → 재검증 대기.
            // -159 에서 바꿀 순서: DELTA 검증을 먼저 만들고 → 그 번호를 넣어 완료 보고를 저장한다.
            // 완료 보고는 UPDATE 금지라 저장 뒤에 delta_validation_id 를 채울 수 없다.
            task.holdForRevalidation();
        }
    }

    /**
     * CANCEL: 큐 취소·점유 해제·늦은 결과 차단 (명세 TSK-A07).
     * 진행 중인 검증 job 은 CANCELLED, 끝나지 않았거나 쓸 수 있던 검증은 trigger 와 관계없이 STALE(TASK_CANCELLED)
     * ({@link ValidationStatusLookup#invalidateAllForTask}). 담당자 인계는 DELTA 를 뺀 검증만 무효화한다 — 같은 SQL 을 거기서 함께 쓴다.
     */
    private void cancel(Task task, Instant now) {
        validations.invalidateAllForTask(task.getId(), "TASK_CANCELLED");
        task.cancel(now);
    }

    private Task requireTask(Long projectId, Long taskId) {
        Optional<Task> task = tasks.findByIdAndProjectId(taskId, projectId);
        if (task.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        return task.get();
    }
}
