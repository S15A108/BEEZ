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
import com.beez.backend.validation.dto.AcceptResult;
import com.beez.backend.validation.service.ValidationAcceptService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 *   <li>전이 적용, 커밋 직전 같은 트랜잭션에서 활동 기록 (TaskStateChangedListener)</li>
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
    private final ValidationAcceptService validationAccepts;
    private final CompletionReviewer completionReviewer;
    private final Clock clock;

    /** 보고가 끊겼다고 보는 시간. 값은 미결(04-task-lifecycle.md §6) — 기본 30분, 설정 beez.task.stalled-after 로 바꾼다 */
    private final Duration stalledAfter;

    public TaskStateService(TaskRepository tasks, TaskCompletionReportRepository reports, ProjectAccessChecker access,
                            ProjectDesignLookup designLookup, TaskTransitionPolicy policy, StartGate startGate,
                            TaskQueryService query, ApplicationEventPublisher events, JdbcClient jdbc,
                            ValidationStatusLookup validations, ValidationAcceptService validationAccepts,
                            CompletionReviewer completionReviewer,
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
        this.validationAccepts = validationAccepts;
        this.completionReviewer = completionReviewer;
        this.clock = Clock.systemUTC();
        this.stalledAfter = stalledAfter;
    }

    @Transactional
    public TaskDetailResponse report(CallerContext caller, Long projectId, Long taskId, TaskStateInput in) {
        ProjectActor actor = access.requireMember(caller, projectId);
        validateShape(in);
        Long validationId = Ids.parse(in.validationId(), "validation_id");
        Long planRevisionId = Ids.parse(in.planRevisionId(), "plan_revision_id");
        // 완료 보고의 "실제로 바꾼 설계 항목" (-269). 이 프로젝트 항목인지 먼저 확인하고 정렬·중복 제거한 모양으로 쓴다
        List<Long> changedItems = completionReviewer.normalizeChangedItems(projectId, in.changedDesignItemIds());

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
            // 응답을 잃고 새 연결로 다시 보낸 START 도 "이 연결이 이 작업을 한다" 는 보고다 — 끊김 판정이 새 연결의 통신을 보게 한다
            recordCurrentTask(caller, projectId, taskId, Instant.now(clock));
            return query.toDetail(task);
        }
        //    delta 있는 COMPLETED 재전송: 지금 기다리는 것이 "그 완료 보고" 이고 요청 내용이 같을 때만 같은 결과(202).
        //    다른 내용이면 409, 기다리는 보고가 없으면(인계 등으로 WAITING 이 된 경우) 아래 전이 표에서 400 (WAITING + COMPLETED = ✗)
        if (in.event() == TaskEvent.COMPLETED) {
            Optional<TaskCompletionReport> pending = pendingDeltaReport(task);
            if (pending.isPresent()) {
                if (sameReport(pending.get(), in, planRevisionId, changedItems)) {
                    return detailOf(task, in.event());
                }
                throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                        "이미 다른 내용의 완료 보고(delta 검증 " + pending.get().getDeltaValidationId() + ")를 기다리는 중입니다. "
                                + "바꾸려면 RESUME 한 뒤 다시 보고하세요");
            }
        }
        TaskTransitionPolicy.Verdict verdict = policy.check(task.getStatus(), in.event());
        if (verdict == TaskTransitionPolicy.Verdict.IDEMPOTENT) {
            if (in.event() == TaskEvent.START) {
                // 진행 중인데 다른 검증으로 START 를 또 보냄 — 멱등이 아니라 충돌
                throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                        "이미 다른 검증(" + task.getStartedValidationId() + ")으로 시작된 작업입니다");
            }
            return detailOf(task, in.event());
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
            case RESUME -> {
                // delta 완료 보고를 거두고 다시 일한다 (§2.3 출구 ①). 그 보고를 기다리던 DELTA 검증은 끝났든 돌고 있든 쓸 수 없게 한다 —
                // 두면 뒤늦게 도착한 옛 DELTA 의 PASS 가 (인계 등으로 다시 WAITING(REVALIDATION) 이 된) 작업을 완료시킬 수 있다
                validations.invalidateDeltaOnResume(taskId);
                task.resume(validationId);
            }
            case WAIT -> task.holdFor(in.waitingReason());
            case COMPLETED -> complete(task, in, changedItems, caller, now);
            case CANCEL -> cancel(task, now);
        }
        tasks.saveAndFlush(task);
        if (in.event() == TaskEvent.START || in.event() == TaskEvent.RESUME) {
            recordCurrentTask(caller, projectId, taskId, now);
        }

        // 활동(계약 6.2)은 커밋 직전 같은 트랜잭션에서 남는다 (TaskStateChangedListener). 위 멱등 반환에서는 나가지 않는다
        events.publishEvent(new TaskStateChangedEvent(caller, projectId, taskId, from, task.getStatus(), task.getWaitingReason(),
                in.event(), task.getLockVersion()));
        return detailOf(task, in.event());
    }

    /**
     * 보고 응답. COMPLETED 보고에는 완료 검증 결과(completion_review, -269)를 붙인다 — 에이전트가 범위 이탈 후보를 바로 본다.
     * 완료 검증은 결과를 보여 주기만 하고 전이를 바꾸지 않는다 (11/5 결정)
     */
    private TaskDetailResponse detailOf(Task task, TaskEvent event) {
        TaskDetailResponse detail = query.toDetail(task);
        if (event != TaskEvent.COMPLETED) {
            return detail;
        }
        return completionReviewer.currentReview(task).map(detail::withCompletionReview).orElse(detail);
    }

    // ------------------------------------------------------------------

    /** 이벤트별 필수 값. 상태를 보기 전에 모양만으로 거를 수 있는 것 */
    private void validateShape(TaskStateInput in) {
        if (in.event() != TaskEvent.COMPLETED && in.changedDesignItemIds() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "changed_design_item_ids 는 COMPLETED 에만 보냅니다");
        }
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
            // 프로젝트 잠금 전에 읽은 역할이다. 팀장 위임(MEM-A08)이 잠금을 쥐고 커밋하는 사이 기다렸다면 이미 팀장이 아닐 수 있어,
            // 잠금 뒤 DB 의 지금 역할로 다시 확인한다 (TSK-A06·MEM-A07 과 같은 규칙, -212 리뷰). 취소는 되돌릴 수 없다
            access.requireLeaderAfterLock(actor);
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
     * 이 요청의 에이전트 연결(MCP 세션)에 "지금 하는 작업" 을 적는다 (agent_session.current_task_id, -336).
     * START·RESUME 이 성공했을 때만 — 그 뒤 이 연결의 통신(last_report_at)이 이 작업의 보고로 쳐진다({@link #isStalled}).
     * 사람 호출·REST 에이전트 호출은 연결 기록이 없어 적지 않는다. 한 연결은 작업 하나만 가리킨다 — 다른 작업을 시작하면 옮겨 간다.
     * 이미 끝난 연결이나 다른 프로젝트 토큰의 연결은 바꾸지 않는다. 프로젝트 잠금 뒤 세션 행을 잠그므로, 세션 행을 먼저 잡는 경로
     * (연결·통신 기록)는 프로젝트를 잠그지 않아 순서가 엇갈리지 않는다.
     */
    private void recordCurrentTask(CallerContext caller, Long projectId, Long taskId, Instant now) {
        if (caller.agentSessionId() == null) {
            return;
        }
        jdbc.sql("""
                        update agent_session s
                           set current_task_id = :taskId, updated_at = :now
                         where s.id = :sessionId
                           and s.ended_at is null
                           and exists (select 1 from agent_token k where k.id = s.agent_token_id and k.project_id = :projectId)
                        """)
                .param("taskId", taskId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("sessionId", caller.agentSessionId())
                .param("projectId", projectId)
                .update();
    }

    /**
     * 보고가 끊긴 작업: <b>IN_PROGRESS</b> 이고 마지막 보고 이후 stalledAfter 가 지났다 (04-task-lifecycle.md §4.1, 담당자 결정).
     * WAITING 은 보고가 멈추는 게 정상이라 넣지 않는다 — 넣으면 팀장 승인을 기다리는 작업까지 팀장이 취소할 수 있게 된다.
     * 마지막 보고 = 작업의 마지막 변경 시각과, 이 작업을 현재 작업으로 보고한 <b>지금 담당자</b> 연결의 마지막 통신(last_report_at) 중 늦은 쪽.
     * 담당자가 바뀐 뒤 이전 담당자의 연결이 계속 통신해도 끊김을 가리지 않게 담당자로 거른다.
     * (greatest 는 Postgres 함수로, NULL 은 무시하고 가장 큰 값을 돌려준다)
     */
    private boolean isStalled(Task task) {
        if (task.getStatus() != TaskStatus.IN_PROGRESS) {
            return false;
        }
        // Postgres 드라이버는 timestamptz 를 OffsetDateTime 으로 읽는다 (Instant 로 바로는 못 읽음)
        OffsetDateTime lastReport = jdbc.sql("""
                        select greatest(t.updated_at,
                                        (select max(s.last_report_at)
                                           from agent_session s
                                           join agent_token k on k.id = s.agent_token_id and k.project_id = t.project_id
                                           join project_member m on m.project_id = k.project_id and m.account_id = k.account_id
                                          where s.current_task_id = t.id
                                            and m.id = t.assignee_member_id))
                          from task t
                         where t.id = :taskId
                        """)
                .param("taskId", task.getId())
                .query(OffsetDateTime.class)
                .single();
        Instant threshold = Instant.now(clock).minus(stalledAfter);
        return lastReport.toInstant().isBefore(threshold);
    }

    /**
     * 지금의 WAITING(REVALIDATION) 이 기다리는 delta 완료 보고 (S3 이후 상태). 다음이 모두 맞을 때만:
     * 작업이 WAITING(REVALIDATION) · 현재 계획에 delta 완료 보고가 있음 · 현재 계획의 가장 최근 DELTA 검증이 STALE 이 아님
     * (RESUME 하면 DELTA 가 STALE(TASK_RESUMED) 이 되므로, 그 뒤 인계로 다시 WAITING(REVALIDATION) 이 된 작업은 옛 보고를 기다리는 것이 아니다).
     * 발행으로 DELTA 가 바뀌어 재접수된 경우는 가장 최근 DELTA 가 살아 있어 같은 보고를 계속 기다리는 것으로 본다
     */
    private Optional<TaskCompletionReport> pendingDeltaReport(Task task) {
        if (task.getStatus() != TaskStatus.WAITING || task.getWaitingReason() != WaitingReason.REVALIDATION) {
            return Optional.empty();
        }
        if (validations.pendingDeltaValidationId(task.getId(), task.getCurrentPlanRevisionId()) == null) {
            return Optional.empty();
        }
        return reports.findFirstByTaskIdAndPlanRevisionIdAndHasDeltaTrueOrderByIdDesc(task.getId(), task.getCurrentPlanRevisionId());
    }

    /** 같은 완료 보고의 재전송인가 — 계획 리비전·delta·요약·완료 기준 확인·바꾼 설계 항목이 모두 같아야 한다 */
    private static boolean sameReport(TaskCompletionReport report, TaskStateInput in, Long planRevisionId,
                                      List<Long> changedItems) {
        if (!Objects.equals(report.getPlanRevisionId(), planRevisionId)) {
            return false;
        }
        List<Map<String, Object>> delta = in.delta();
        if (delta == null) {
            delta = new ArrayList<>();
        }
        if (!Objects.equals(report.getDelta(), delta) || !Objects.equals(report.getSummary(), in.summary())) {
            return false;
        }
        // 둘 다 정렬·중복 제거된 목록이다. null(보고 안 함)과 빈 목록(바꾼 것 없음)은 다른 보고로 본다
        if (!Objects.equals(report.getChangedDesignItemIds(), changedItems)) {
            return false;
        }
        return Objects.equals(report.getCompletionChecks(), checksOf(in));
    }

    /** 완료 기준 확인을 저장 모양({criterion, met})으로 */
    private static List<Map<String, Object>> checksOf(TaskStateInput in) {
        List<Map<String, Object>> checks = new ArrayList<>();
        if (in.completionChecks() != null) {
            for (CompletionCheck c : in.completionChecks()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("criterion", c.criterion());
                row.put("met", c.met());
                checks.add(row);
            }
        }
        return checks;
    }

    private void complete(Task task, TaskStateInput in, List<Long> changedItems, CallerContext caller, Instant now) {
        List<Map<String, Object>> delta = in.delta();
        if (delta == null) {
            delta = new ArrayList<>();
        }
        // 완료 기준 확인은 형식 검사를 거친 CompletionCheck 로 받고, 저장(JSONB)은 {criterion, met} 로 한다
        List<Map<String, Object>> checks = checksOf(in);
        if (delta.isEmpty()) {
            reports.save(new TaskCompletionReport(task.getId(), task.getCurrentPlanRevisionId(), delta, in.summary(),
                    checks, changedItems, caller.agentTokenId()));
            task.complete(now);
            return;
        }
        // 계획과 다른 점이 있다 → DELTA 검증을 먼저 접수하고(S3) 그 번호를 넣어 완료 보고를 저장한다
        // (완료 보고는 UPDATE 금지라 저장 뒤에 delta_validation_id 를 채울 수 없다). 같은 트랜잭션·같은 프로젝트 잠금 안이다
        // DELTA 검증은 delta 내용 자체를 의미 분석(완료 delta 검사)에 넣으므로 규칙만으로 PASS 가 나지 않는다 — 여기서 바로 완료되는 일은 없다.
        // 규칙으로 NEEDS_INPUT·NEEDS_REVIEW 가 나면 그대로 WAITING(REVALIDATION) 에서 §2.3 출구로 나간다
        AcceptResult accepted = validationAccepts.acceptBySystem(caller, task.getProjectId(), task, "DELTA", delta);
        long deltaValidationId = Long.parseLong(accepted.validation().id());
        reports.save(new TaskCompletionReport(task.getId(), task.getCurrentPlanRevisionId(), delta, in.summary(),
                checks, changedItems, caller.agentTokenId(), deltaValidationId));
        task.holdForRevalidation();
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
