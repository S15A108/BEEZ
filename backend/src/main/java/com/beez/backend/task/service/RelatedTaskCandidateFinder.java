package com.beez.backend.task.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds related open task candidates using current plan references and explicit task dependencies.
 * A path is evidence of a recorded relationship, not proof of a conflict. This service performs
 * no task text or vector search, so an empty result does not prove there are no related tasks.
 * The caller must retain the searched baseline and recheck it and the returned plan revisions
 * before committing or using a validation result.
 */
@Service
public class RelatedTaskCandidateFinder {
    private static final int MAX_DESIGN_IDS = 50;
    private static final int MAX_TASKS = 50;
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;

    public RelatedTaskCandidateFinder(JdbcClient jdbc, ProjectAccessChecker access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record Path(String kind, Long designItemId, Long designItemVersionId, String relation) {}
    public record Candidate(long taskId, long planRevisionId, Long assigneeMemberId, List<Path> paths) {
        public Candidate { paths = List.copyOf(paths); }
    }
    public enum Coverage { RECORDED_RELATIONSHIPS_ONLY }
    public record Result(long baselineId, long targetPlanRevisionId, List<Candidate> candidates,
                         boolean truncated, Coverage coverage) {
        public Result { candidates = List.copyOf(candidates); }
    }

    /** Only approved designs in the current project baseline may select tasks. */
    @Transactional(readOnly = true)
    public Result find(CallerContext caller, long projectId, long taskId, long planRevisionId,
                       List<Long> designItemIds, int limit) {
        access.requireMember(caller, projectId);
        if (designItemIds == null || designItemIds.size() > MAX_DESIGN_IDS
                || designItemIds.stream().anyMatch(id -> id == null || id <= 0)
                || limit < 1 || limit > MAX_TASKS) {
            throw new IllegalArgumentException("Invalid candidate search bounds or design IDs");
        }
        var target = jdbc.sql("""
                select t.current_plan_revision_id, p.current_baseline_id
                from task t join project p on p.id = t.project_id
                where t.project_id = :project and t.id = :task
                """).param("project", projectId).param("task", taskId).query().listOfRows();
        if (target.isEmpty()) throw AccessErrors.notFound("task_not_in_project");
        var current = target.getFirst();
        if (current.get("current_plan_revision_id") == null
                || ((Number) current.get("current_plan_revision_id")).longValue() != planRevisionId) {
            throw new ApiException(HttpStatus.CONFLICT, "stale_plan", "작업 계획이 변경되었습니다");
        }
        if (current.get("current_baseline_id") == null) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_not_found", "승인 기준이 없습니다");
        }
        long baselineId = ((Number) current.get("current_baseline_id")).longValue();
        // An impossible ID avoids generating SQL with an empty IN clause.
        List<Long> ids = designItemIds.isEmpty() ? List.of(-1L) : List.copyOf(new LinkedHashSet<>(designItemIds));
        List<Map<String, Object>> rows = jdbc.sql("""
                with paths as (
                    select t.id as task_id, t.current_plan_revision_id as revision_id, t.assignee_member_id,
                           'DESIGN_REFERENCE' as path_kind, ref.design_item_id,
                           ref.design_item_version_id, ref.relation
                      from task t
                      join task_plan_design_ref ref on ref.plan_revision_id = t.current_plan_revision_id
                      join baseline_item bi on bi.baseline_id = :baseline and bi.design_item_id = ref.design_item_id
                     where t.project_id = :project and t.id <> :task
                       and t.status in ('DRAFT', 'READY', 'IN_PROGRESS', 'WAITING')
                       and ref.design_item_id in (:ids)
                    union all
                    select other.id, other.current_plan_revision_id, other.assignee_member_id,
                           case when dep.task_id = :task then 'DEPENDS_ON' else 'DEPENDENT_TASK' end,
                           null, null, dep.kind
                      from task_dependency dep
                      join task other on other.project_id = dep.project_id
                         and other.id = case when dep.task_id = :task then dep.depends_on_task_id else dep.task_id end
                     where dep.project_id = :project and (dep.task_id = :task or dep.depends_on_task_id = :task)
                       and other.id <> :task and other.current_plan_revision_id is not null
                       and other.status in ('DRAFT', 'READY', 'IN_PROGRESS', 'WAITING')
                ), ranked as (
                    select *, dense_rank() over (order by task_id) as candidate_rank from paths
                )
                select task_id, revision_id, assignee_member_id, path_kind,
                       design_item_id, design_item_version_id, relation
                  from ranked where candidate_rank <= :maximum
                 order by task_id, path_kind, design_item_id
                """)
                .param("project", projectId).param("task", taskId).param("baseline", baselineId)
                .param("ids", ids).param("maximum", limit + 1).query().listOfRows();
        access.requireMember(caller, projectId);
        boolean unchanged = jdbc.sql("""
                select count(*) from task t join project p on p.id=t.project_id
                 where p.id=:project and t.id=:task and t.current_plan_revision_id=:revision
                   and p.current_baseline_id=:baseline
                """).param("project", projectId).param("task", taskId).param("revision", planRevisionId)
                .param("baseline", baselineId).query(Long.class).single() == 1L;
        if (!unchanged) {
            throw new ApiException(HttpStatus.CONFLICT, "stale_context", "검색 중 작업 계획 또는 승인 기준이 변경되었습니다");
        }
        return collect(baselineId, planRevisionId, rows, limit);
    }

    static Result collect(long baselineId, long revisionId, List<Map<String, Object>> rows, int limit) {
        Map<Long, List<Path>> paths = new LinkedHashMap<>();
        Map<Long, Long> revisions = new LinkedHashMap<>();
        Map<Long, Long> assignees = new LinkedHashMap<>();
        boolean truncated = false;
        for (var row : rows) {
            long taskId = ((Number) row.get("task_id")).longValue();
            if (!paths.containsKey(taskId) && paths.size() == limit) { truncated = true; continue; }
            paths.computeIfAbsent(taskId, ignored -> new ArrayList<>()).add(new Path(
                    (String) row.get("path_kind"), number(row.get("design_item_id")),
                    number(row.get("design_item_version_id")), (String) row.get("relation")));
            revisions.put(taskId, number(row.get("revision_id")));
            assignees.put(taskId, number(row.get("assignee_member_id")));
        }
        List<Candidate> candidates = new ArrayList<>();
        paths.forEach((id, candidatePaths) -> candidates.add(new Candidate(id, revisions.get(id),
                assignees.get(id), candidatePaths)));
        return new Result(baselineId, revisionId, candidates, truncated, Coverage.RECORDED_RELATIONSHIPS_ONLY);
    }

    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
}
