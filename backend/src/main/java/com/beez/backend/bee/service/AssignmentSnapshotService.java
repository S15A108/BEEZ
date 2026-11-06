package com.beez.backend.bee.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.MemberRow;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 모델 호출 전후에 각각 짧은 DB 스냅샷을 읽는다. 모델 대기 동안 DB 트랜잭션을 잡지 않는다. */
@Service
public class AssignmentSnapshotService {
    static final int MAX_TASKS = 20;
    static final int MAX_MEMBERS = 100;
    static final int MAX_CURRENT = 500;
    private static final List<TaskStatus> OPEN = List.of(TaskStatus.READY, TaskStatus.IN_PROGRESS, TaskStatus.WAITING);

    private final ProjectAccessChecker access;
    private final ProjectMemberRepository members;
    private final TaskRepository tasks;
    private final TaskPlanRevisionRepository revisions;

    public AssignmentSnapshotService(ProjectAccessChecker access, ProjectMemberRepository members,
                                     TaskRepository tasks, TaskPlanRevisionRepository revisions) {
        this.access = access;
        this.members = members;
        this.tasks = tasks;
        this.revisions = revisions;
    }

    public record Snapshot(Map<String, Object> input, Set<String> taskIds, Set<String> memberIds) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot capture(CallerContext caller, long projectId, List<Long> ids, boolean validateTargets) {
        access.requireMember(caller, projectId).requireHuman();
        List<MemberRow> memberRows = members.findActiveMembers(projectId, Long.MAX_VALUE, PageRequest.of(0, MAX_MEMBERS + 1));
        if (memberRows.size() > MAX_MEMBERS) throw invalid("팀원은 100명 이하일 때 추천할 수 있습니다.");
        List<Map<String, Object>> memberInput = memberRows.stream().map(MemberRow::member)
                .sorted(Comparator.comparing(ProjectMember::getId)).map(AssignmentSnapshotService::member).toList();

        Map<Long, Task> byId = new LinkedHashMap<>();
        for (Task task : tasks.findByProjectIdAndIdIn(projectId, ids)) byId.put(task.getId(), task);
        if (byId.size() != ids.size()) throw AccessErrors.notFound("assignment_target_missing project=" + projectId);
        List<Task> targets = ids.stream().map(byId::get).toList();
        if (validateTargets && targets.stream().anyMatch(t -> t.getStatus() == TaskStatus.COMPLETED || t.getStatus() == TaskStatus.CANCELLED)) {
            throw invalid("완료하거나 취소한 작업은 추천할 수 없습니다.");
        }

        List<Long> revisionIds = targets.stream().map(Task::getCurrentPlanRevisionId).filter(id -> id != null).toList();
        Map<Long, TaskPlanRevision> plans = new LinkedHashMap<>();
        if (!revisionIds.isEmpty()) {
            for (TaskPlanRevision plan : revisions.findByIdIn(revisionIds)) plans.put(plan.getId(), plan);
        }
        List<Map<String, Object>> targetInput = new ArrayList<>();
        Set<Long> dependencyIds = new LinkedHashSet<>();
        for (Task target : targets) {
            Map<String, Object> input = task(target);
            TaskPlanRevision plan = plans.get(target.getCurrentPlanRevisionId());
            if (plan != null && plan.getTaskId().equals(target.getId())) {
                Map<String, Object> planInput = new LinkedHashMap<>();
                planInput.put("title", plan.getTitle());
                putText(planInput, "goal", plan.getGoal());
                planInput.put("included_scope", plan.getIncludedScope());
                putText(planInput, "approach", plan.getApproach());
                planInput.put("dependency_task_ids", Ids.strs(plan.getDependencyTaskIds()));
                dependencyIds.addAll(plan.getDependencyTaskIds());
                input.put("current_plan_revision", Map.of("id", Ids.str(plan.getId()), "task_id", Ids.str(target.getId()), "plan", planInput));
            }
            targetInput.add(input);
        }

        List<Task> open = tasks.findByProjectIdAndStatusInAndAssigneeMemberIdIsNotNullOrderByIdAsc(
                projectId, OPEN, PageRequest.of(0, MAX_CURRENT + 1));
        boolean complete = open.size() <= MAX_CURRENT;
        Map<Long, Task> observed = new LinkedHashMap<>();
        open.stream().limit(MAX_CURRENT).forEach(t -> observed.put(t.getId(), t));
        if (!dependencyIds.isEmpty() && observed.size() < MAX_CURRENT) {
            tasks.findByProjectIdAndIdIn(projectId, new ArrayList<>(dependencyIds)).stream()
                    .sorted(Comparator.comparing(Task::getId)).filter(t -> !observed.containsKey(t.getId()))
                    .limit(MAX_CURRENT - observed.size()).forEach(t -> observed.put(t.getId(), t));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("project_id", Ids.str(projectId));
        input.put("tasks", targetInput);
        input.put("members", memberInput);
        input.put("current_tasks", observed.values().stream().map(AssignmentSnapshotService::task).toList());
        input.put("current_tasks_complete", complete);
        return new Snapshot(input,
                new LinkedHashSet<>(ids.stream().map(Ids::str).toList()),
                new LinkedHashSet<>(memberRows.stream().map(row -> Ids.str(row.member().getId())).toList()));
    }

    private static Map<String, Object> task(Task task) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Ids.str(task.getId()));
        result.put("project_id", Ids.str(task.getProjectId()));
        result.put("status", task.getStatus().name());
        result.put("lock_version", task.getLockVersion());
        if (task.getAssigneeMemberId() != null) result.put("assignee_member_id", Ids.str(task.getAssigneeMemberId()));
        return result;
    }

    private static Map<String, Object> member(ProjectMember member) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Ids.str(member.getId()));
        result.put("role", member.getRole().name());
        putText(result, "domain", member.getArea());
        putText(result, "role_label", member.getRoleLabel());
        return result;
    }

    private static void putText(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message);
    }
}
