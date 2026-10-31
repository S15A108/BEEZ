package com.beez.backend.task.service;

import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskPlanInput;
import com.beez.backend.task.entity.DependencyKind;
import com.beez.backend.task.entity.PlanRelation;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskDependency;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.repository.TaskDependencyRepository;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 생성(TSK-A01)과 계획 리비전 추가(TSK-A04 / MCP upsert_task_plan).
 *
 * <p>이 클래스가 규칙의 본체다. REST 컨트롤러와 MCP 어댑터(전진)가 같은 메서드를 부른다. 권한 검사도 여기서 한다.
 * 규칙 출처: docs/design/04-task-lifecycle.md §4.2·§4.3, docs/api/01-backend-rest.md TSK-A01·A04.
 */
@Service
public class TaskPlanService {

    private final TaskRepository tasks;
    private final TaskPlanRevisionRepository revisions;
    private final TaskPlanDesignRefRepository designRefs;
    private final TaskDependencyRepository dependencies;
    private final ProjectAccessChecker access;
    private final ProjectDesignLookup designLookup;
    private final ValidationStatusLookup validationLookup;
    private final ContextPacketLookup packetLookup;
    private final PlanInputHasher hasher;
    private final TaskQueryService query;
    private final JdbcClient jdbc;

    public TaskPlanService(TaskRepository tasks, TaskPlanRevisionRepository revisions,
                           TaskPlanDesignRefRepository designRefs, TaskDependencyRepository dependencies,
                           ProjectAccessChecker access, ProjectDesignLookup designLookup,
                           ValidationStatusLookup validationLookup, ContextPacketLookup packetLookup,
                           PlanInputHasher hasher, TaskQueryService query, JdbcClient jdbc) {
        this.tasks = tasks;
        this.revisions = revisions;
        this.designRefs = designRefs;
        this.dependencies = dependencies;
        this.access = access;
        this.designLookup = designLookup;
        this.validationLookup = validationLookup;
        this.packetLookup = packetLookup;
        this.hasher = hasher;
        this.query = query;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------
    // TSK-A01 작업 생성 = 작업 행 + 첫 계획 리비전. 끝나면 READY.
    // ------------------------------------------------------------------
    @Transactional
    public TaskDetailResponse createTask(CallerContext caller, Long projectId, TaskPlanInput input) {
        ProjectActor actor = access.requireMember(caller, projectId);
        PlanCommand plan = PlanCommand.from(input); // 문자열 ID → Long (형식 오류 400)

        // 1) 프로젝트 행 잠금 → 표시 키 발급이 겹치지 않게. UNIQUE(project_id, key) 가 최후 방어.
        boolean exists = designLookup.lockProject(projectId);
        if (!exists) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        // 팀원 제외·나가기(MEM-A09·A10)도 이 행을 잠근다. 잠그기 전에 그쪽이 커밋됐으면 호출자는 이미 멤버가 아니다 —
        // 그대로 만들면 나간 사람이 담당인 열린 작업이 남는다
        if (!access.isActiveMember(actor.memberId(), projectId)) {
            throw AccessErrors.notFound("member_left project=" + projectId + " member=" + actor.memberId());
        }
        String key = nextTaskKey(projectId);

        // 2) 입력 검증 (DB 에 닿기 전에 400 으로 거른다)
        ResolvedRefs resolved = resolveDesignRefs(projectId, plan.designRefs());
        if (plan.contextPacketId() != null) {
            packetLookup.requireBelongsTo(plan.contextPacketId(), projectId, null);
        }

        // 3) 작업 저장 (DRAFT). 담당자 = 호출자의 멤버 번호 (명세 TSK-A01)
        Task task = tasks.save(Task.create(projectId, key, plan.title(), actor.memberId()));

        // 4) 선행 작업 검증은 task id 가 생긴 뒤에 (자기 자신 검사 때문)
        validateDependencies(projectId, task.getId(), plan.dependencyTaskIds());

        // 5) 첫 리비전 + 참조 + 선행 관계 저장, READY 로
        TaskPlanRevision revision = saveRevision(task, 1, plan, resolved, caller);
        replaceDependencies(projectId, task.getId(), plan.dependencyTaskIds());
        task.applyNewPlanRevision(revision.getId(), plan.title());
        // 지금 flush 해서 응답의 lock_version 이 DB 와 같게 한다 (flush 전에는 @Version 이 아직 안 올라가 있다)
        tasks.saveAndFlush(task);

        return query.toDetail(task);
    }

    // ------------------------------------------------------------------
    // TSK-A04 계획 리비전 추가. 덮어쓰지 않고 새 행.
    // ------------------------------------------------------------------
    @Transactional
    public TaskDetailResponse addRevision(CallerContext caller, Long projectId, Long taskId, TaskPlanInput input,
                                          int expectedRevisionNo, int expectedLockVersion) {
        ProjectActor actor = access.requireMember(caller, projectId);
        PlanCommand plan = PlanCommand.from(input); // 문자열 ID → Long (형식 오류 400)
        // 프로젝트 행을 먼저 잠근다. 같은 작업의 계획을 동시에 고치면 두 번째 요청은 여기서 기다렸다가
        // 아래 revision_no 비교에서 409 revision_conflict 를 받는다 (잠그지 않으면 UNIQUE(task_id, revision_no) 위반이 400 으로 샌다).
        // 잠금 순서를 상태 보고(START 등)와 같은 "프로젝트 → 작업" 으로 맞춰 교착을 피하고,
        // 진행 중 계획 변경과 다른 작업의 START 가 엇갈려 같은 항목을 함께 CHANGES 점유하는 틈도 막는다.
        boolean exists = designLookup.lockProject(projectId);
        if (!exists) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        Task task = requireTask(projectId, taskId);
        requireAssignee(task, actor);

        if (task.getStatus().isClosed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition",
                    "완료·취소된 작업의 계획은 바꿀 수 없습니다 (현재 " + task.getStatus() + ")");
        }

        // 충돌 검사 두 가지 — 명세 00-common 5절. 둘 다 409 revision_conflict.
        int currentRevisionNo = revisions.findMaxRevisionNo(taskId);
        if (currentRevisionNo != expectedRevisionNo) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "계획이 이미 바뀌었습니다 (현재 revision_no=" + currentRevisionNo + ")");
        }
        if (task.getLockVersion() != expectedLockVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "작업 상태가 이미 바뀌었습니다 (현재 lock_version=" + task.getLockVersion() + ")");
        }

        ResolvedRefs resolved = resolveDesignRefs(projectId, plan.designRefs());
        if (plan.contextPacketId() != null) {
            packetLookup.requireBelongsTo(plan.contextPacketId(), projectId, taskId);
        }
        validateDependencies(projectId, taskId, plan.dependencyTaskIds());

        TaskPlanRevision revision = saveRevision(task, currentRevisionNo + 1, plan, resolved, caller);
        replaceDependencies(projectId, taskId, plan.dependencyTaskIds());
        TaskStatus before = task.getStatus();
        task.applyNewPlanRevision(revision.getId(), plan.title());

        // 계획이 바뀌었으니 이전 검증은 쓸 수 없다 (점유 중인 작업만 의미 있음. READY 는 G1 이 리비전으로 거른다)
        // 상태는 IN_PROGRESS 만 WAITING(REVALIDATION) 으로 (명세 TSK-A04 "진행 중 계획 변경", 04-task-lifecycle.md §2.2 S2).
        // WAITING(INPUT·APPROVAL·DEPENDENCY) 은 원래 사유를 덮어쓰지 않는다 — RESUME 때 G1 이 새 PASS 를 요구한다.
        if (before.isOccupied()) {
            validationLookup.markStaleForTask(taskId, "PLAN_CHANGED");
        }
        if (before == TaskStatus.IN_PROGRESS) {
            task.holdForRevalidation();
        }
        tasks.saveAndFlush(task);

        return query.toDetail(task);
    }

    // ------------------------------------------------------------------
    // 내부 단계
    // ------------------------------------------------------------------

    /** T-1, T-2 … 프로젝트 안에서 가장 큰 번호 + 1. lockProject 뒤에만 부른다. */
    private String nextTaskKey(Long projectId) {
        Integer max = jdbc.sql("""
                        select coalesce(max(cast(substring(key from 3) as integer)), 0)
                          from task
                         where project_id = :projectId
                           and key ~ '^T-[0-9]+$'
                        """)
                .param("projectId", projectId)
                .query(Integer.class)
                .single();
        return "T-" + (max + 1);
    }

    /**
     * 참조 목록을 정리하고 버전을 확인한다.
     * <ul>
     *   <li>같은 항목이 여러 번 오면 센 관계 하나만 (CHANGES > IMPLEMENTS > READS)</li>
     *   <li>CHANGES 면 proposed_content 필수, 아니면 금지 → 400 validation_failed</li>
     *   <li>항목이 현재 baseline 에 없으면 400, 보낸 버전이 현재 버전과 다르면 409 version_conflict</li>
     * </ul>
     */
    private ResolvedRefs resolveDesignRefs(Long projectId, List<PlanCommand.Ref> inputs) {
        // LinkedHashMap: 들어온 순서를 유지한다 (응답에서 순서가 뒤섞이지 않게)
        Map<Long, PlanCommand.Ref> strongest = new LinkedHashMap<>();
        for (PlanCommand.Ref in : inputs) {
            boolean hasContent = in.proposedContent() != null && !in.proposedContent().isEmpty();
            if (in.relation() == PlanRelation.CHANGES && !hasContent) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "CHANGES 참조에는 proposed_content 가 필요합니다 (design_item_id=" + in.designItemId() + ")");
            }
            if (in.relation() != PlanRelation.CHANGES && hasContent) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "proposed_content 는 CHANGES 에서만 보냅니다 (design_item_id=" + in.designItemId() + ")");
            }
            PlanCommand.Ref existing = strongest.get(in.designItemId());
            if (existing == null || in.relation().isStrongerThan(existing.relation())) {
                strongest.put(in.designItemId(), in);
            }
        }

        Map<Long, Long> versionByItem = new HashMap<>();
        Map<Long, PlanRelation> relationByItem = new HashMap<>();
        for (PlanCommand.Ref in : strongest.values()) {
            Optional<Long> current = designLookup.currentVersionIdOf(projectId, in.designItemId());
            if (current.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "현재 기준 설계에 없거나 폐기된 항목입니다 (design_item_id=" + in.designItemId() + ")");
            }
            if (!current.get().equals(in.designItemVersionId())) {
                throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
                        "설계 참조가 오래됨 (design_item_id=" + in.designItemId()
                                + ", 현재 버전=" + current.get() + ")");
            }
            versionByItem.put(in.designItemId(), current.get());
            relationByItem.put(in.designItemId(), in.relation());
        }
        return new ResolvedRefs(new ArrayList<>(strongest.values()), versionByItem, relationByItem);
    }

    /**
     * 선행 작업 검사 (명세 TSK-A01·A04 오류표).
     * 자기 자신·다른 프로젝트 작업은 400 validation_failed, A↔B 1단계 순환은 409 dependency_cycle.
     * 더 긴 순환(A→B→C→A) 탐지는 미결(04-task-lifecycle.md §6).
     */
    private void validateDependencies(Long projectId, Long taskId, List<Long> dependencyTaskIds) {
        if (dependencyTaskIds.isEmpty()) {
            return;
        }
        Set<Long> unique = new HashSet<>(dependencyTaskIds);
        if (unique.contains(taskId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "작업이 자기 자신을 선행으로 가질 수 없습니다");
        }
        List<Task> found = tasks.findByProjectIdAndIdIn(projectId, new ArrayList<>(unique));
        if (found.size() != unique.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "선행 작업 중 같은 프로젝트에 없는 것이 있습니다");
        }
        // 1단계 순환: 내가 B 를 선행으로 두려는데, B 가 이미 나를 선행으로 두고 있으면 거부
        for (TaskDependency reverse : dependencies.findByDependsOnTaskId(taskId)) {
            if (unique.contains(reverse.getTaskId())) {
                throw new ApiException(HttpStatus.CONFLICT, "dependency_cycle",
                        "선행 관계가 순환합니다 (task_id=" + reverse.getTaskId() + ")");
            }
        }
    }

    private TaskPlanRevision saveRevision(Task task, int revisionNo, PlanCommand plan, ResolvedRefs resolved,
                                          CallerContext caller) {
        String inputHash = hasher.hash(plan, resolved.versionByItem(), resolved.relationByItem());
        // 저장 당시 기준 설계. 호출자가 프로젝트 행을 잠근 상태라 발행과 겹치지 않는다
        Long baseBaselineId = null;
        Optional<Long> currentBaseline = designLookup.currentBaselineId(task.getProjectId());
        if (currentBaseline.isPresent()) {
            baseBaselineId = currentBaseline.get();
        }
        TaskPlanRevision revision = TaskPlanRevision.builder()
                .taskId(task.getId())
                .revisionNo(revisionNo)
                .title(plan.title())
                .goal(plan.goal())
                .includedScope(plan.includedScope())
                .excludedScope(plan.excludedScope())
                .approach(plan.approach())
                .dependencyTaskIds(plan.dependencyTaskIds())
                .unlinked(plan.unlinked())
                .noContractChange(plan.noContractChange())
                .completionCriteria(plan.completionCriteria())
                .inputHash(inputHash)
                .createdByAccountId(caller.accountId())
                .createdByTokenId(caller.agentTokenId())
                .contextPacketId(plan.contextPacketId())
                .baseBaselineId(baseBaselineId)
                .build();
        revision = revisions.save(revision);

        List<TaskPlanDesignRef> refRows = new ArrayList<>();
        for (PlanCommand.Ref in : resolved.refs()) {
            refRows.add(new TaskPlanDesignRef(revision.getId(), in.designItemId(),
                    resolved.versionByItem().get(in.designItemId()), in.relation(), in.proposedContent()));
        }
        designRefs.saveAll(refRows);
        return revision;
    }

    /** task_dependency 를 이 계획의 선행 목록으로 덮어쓴다. 스냅샷은 리비전에 따로 남아 있다. */
    private void replaceDependencies(Long projectId, Long taskId, List<Long> dependencyTaskIds) {
        dependencies.deleteAllByTaskId(taskId);
        List<TaskDependency> rows = new ArrayList<>();
        for (Long dependsOn : new HashSet<>(dependencyTaskIds)) {
            rows.add(new TaskDependency(projectId, taskId, dependsOn, DependencyKind.BLOCKS));
        }
        dependencies.saveAll(rows);
    }

    private Task requireTask(Long projectId, Long taskId) {
        Optional<Task> task = tasks.findByIdAndProjectId(taskId, projectId);
        if (task.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        return task.get();
    }

    /** 계획 변경은 담당자 본인만 (사람 세션이든 그 사람의 에이전트 토큰이든 멤버 번호가 같으면 본인) */
    private void requireAssignee(Task task, ProjectActor actor) {
        actor.requireMemberId(task.getAssigneeMemberId(), "담당자만 계획을 바꿀 수 있습니다");
    }

    /** 정리된 참조 목록과, 항목별 확정 버전·관계 */
    private record ResolvedRefs(List<PlanCommand.Ref> refs, Map<Long, Long> versionByItem,
                                Map<Long, PlanRelation> relationByItem) {
    }
}
