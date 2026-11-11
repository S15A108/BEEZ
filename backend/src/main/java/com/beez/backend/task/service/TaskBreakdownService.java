package com.beez.backend.task.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.ai.AiErrorBodies;
import com.beez.backend.ai.AiUsageRecorder;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.task.dto.TaskBreakdownJobResponse;
import com.beez.backend.task.dto.TaskBreakdownJobResponse.Status;
import com.beez.backend.task.dto.TaskBreakdownResponse;
import com.beez.backend.task.dto.TaskBreakdownResponse.GeneratedTask;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.service.TaskBreakdownJobRepository.Claim;
import com.beez.backend.task.service.TaskBreakdownJobRepository.Job;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Captures an approved design for the worker, which saves the initial backlog as one batch. */
@Service
public class TaskBreakdownService {
    private static final Logger log = LoggerFactory.getLogger(TaskBreakdownService.class);
    private static final int MAX_ATTEMPTS = 2;
    private static final String USAGE_FEATURE = "BEE_DRAFT";
    private static final int MAX_ITEMS = 80;
    private static final int MAX_DOCUMENTS = 8;
    private static final int MAX_AREAS = 60;
    private static final int MAX_EXCERPT = 12_000;
    private static final int DOCUMENT_BUDGET = 24_000;
    private static final int CONTEXT_BUDGET = 72_000;

    private final ProjectAccessChecker access;
    private final ProjectDesignLookup designLookup;
    private final TaskPlanService plans;
    private final TaskAssigneeService assignees;
    private final AiClient ai;
    private final AiUsageRecorder usage;
    private final JdbcClient jdbc;
    private final TaskBreakdownJobRepository jobs;
    private final TransactionTemplate transaction;
    private final int leaseSeconds;

    public TaskBreakdownService(ProjectAccessChecker access, ProjectDesignLookup designLookup, TaskPlanService plans,
                                TaskAssigneeService assignees, AiClient ai, AiUsageRecorder usage, JdbcClient jdbc,
                                TaskBreakdownJobRepository jobs, PlatformTransactionManager transactionManager,
                                @Value("${beez.task-breakdown.read-timeout:105s}") Duration readTimeout) {
        this.access = access;
        this.designLookup = designLookup;
        this.plans = plans;
        this.assignees = assignees;
        this.ai = ai;
        this.usage = usage;
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactionManager);
        this.leaseSeconds = Math.toIntExact(readTimeout.plusMinutes(1).toSeconds());
    }

    public TaskBreakdownJobResponse accept(CallerContext caller, long projectId) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        return transaction.execute(status -> {
            lockProject(projectId);
            access.requireLeaderAfterLock(actor);
            Job latest = jobs.latest(projectId).orElse(null);
            if (latest != null && latest.status() != Status.FAILED) return response(latest);

            TaskBreakdownResponse existing = savedBatch(projectId);
            if (existing != null) {
                Job job = jobs.recordExistingBatch(projectId, Long.parseLong(existing.baselineId()));
                return response(job, existing.tasks());
            }
            long baselineId = designLookup.requireCurrentBaseline(projectId);
            Snapshot snapshot = captureSnapshot(projectId, baselineId);
            return response(jobs.enqueue(projectId, baselineId, actor.accountId(), DesignJson.canonical(snapshot)));
        });
    }

    public TaskBreakdownJobResponse latest(CallerContext caller, long projectId) {
        access.requireHumanLeader(caller, projectId);
        return jobs.latest(projectId).map(this::response).orElse(null);
    }

    private Snapshot captureSnapshot(long projectId, long baselineId) {
        List<DesignItem> items = approvedDesigns(projectId, baselineId);
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "design_items_missing", "승인된 설계 항목이 있어야 작업을 만들 수 있어요.");
        }
        if (items.size() > MAX_ITEMS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "task_breakdown_input_limit",
                    "작업을 생성할 수 있는 설계 항목은 최대 80개예요.");
        }
        List<ApprovedDocument> documents = approvedDocuments(projectId, baselineId);
        List<String> areas = loadMembers(projectId).stream().flatMap(member -> member.areas().stream())
                .distinct().sorted().limit(MAX_AREAS).toList();
        Map<String, String> designVersions = items.stream().collect(Collectors.toMap(DesignItem::id, DesignItem::versionId));
        return new Snapshot(modelInput(projectId, documents, items, areas), designVersions, areas);
    }

    @Transactional(propagation = Propagation.NEVER)
    public boolean runNext() {
        Claim claim = transaction.execute(status -> jobs.claim(leaseSeconds, MAX_ATTEMPTS).orElse(null));
        if (claim == null) return false;
        long projectId = claim.job().projectId();
        CallerContext caller = CallerContext.human(claim.requestedBy());
        try {
            access.requireHumanLeader(caller, projectId);
            List<TaskBreakdownDraft> drafts = List.of();
            if (savedBatch(projectId) == null) {
                requireBaseline(projectId, claim.job().baselineId());
                Snapshot snapshot = DesignJson.JSON.readValue(claim.inputSnapshot(), Snapshot.class);
                String requestKey = "task-breakdown-" + claim.job().id() + "-" + claim.leaseToken();
                Map<String, Object> input = new HashMap<>(snapshot.input());
                input.put("request_key", requestKey);
                drafts = generateDrafts(projectId, requestKey, input, snapshot.designVersions(),
                        Set.copyOf(snapshot.assignmentAreas()));
            }
            complete(claim, caller, drafts);
        } catch (RuntimeException failure) {
            // Deployment interruption leaves the lease recoverable by a new worker.
            if (interrupted(failure)) return true;
            String code = failure instanceof ApiException api ? api.getCode() : "ai_unavailable";
            if (!(failure instanceof ApiException)) {
                log.warn("Task breakdown job {} failed ({})", claim.job().id(), failure.getClass().getSimpleName());
            }
            transaction.executeWithoutResult(status -> jobs.fail(claim, code));
        }
        return true;
    }

    private void complete(Claim claim, CallerContext caller, List<TaskBreakdownDraft> drafts) {
        long projectId = claim.job().projectId();
        transaction.executeWithoutResult(status -> {
            lockProject(projectId);
            if (!jobs.holdLease(claim)) return;
            ProjectActor actor = access.requireHumanLeader(caller, projectId);
            access.requireLeaderAfterLock(actor);

            TaskBreakdownResponse saved = savedBatch(projectId);
            if (saved == null) {
                requireBaseline(projectId, claim.job().baselineId());
                saved = save(caller, projectId, claim.job().baselineId(), drafts);
            }
            jobs.complete(claim, Long.parseLong(saved.baselineId()));
        });
    }

    private void lockProject(long projectId) {
        if (!designLookup.lockProject(projectId)) throw AccessErrors.notFound("project_missing project=" + projectId);
    }

    private void requireBaseline(long projectId, long baselineId) {
        if (baselineId != designLookup.requireCurrentBaseline(projectId)) {
            throw new ApiException(HttpStatus.CONFLICT, "task_breakdown_stale", "승인 기준이 바뀌었어요. 다시 작업을 생성해 주세요.");
        }
    }

    private List<TaskBreakdownDraft> generateDrafts(long projectId, String requestKey, Map<String, Object> input,
                                                     Map<String, String> designVersions, Set<String> areas) {
        long started = System.nanoTime();
        Map<String, Object> response;
        try {
            response = ai.taskBreakdown(input);
        } catch (RestClientException e) {
            if (interrupted(e)) throw e;
            long duration = elapsed(started);
            boolean timeout = e.getCause() instanceof HttpTimeoutException || e.getCause() instanceof SocketTimeoutException;
            String aiErrorCode = null;
            List<Map<String, Object>> entries = null;
            if (e instanceof RestClientResponseException httpError) {
                timeout = httpError.getStatusCode().value() == 504;
                aiErrorCode = AiErrorBodies.codeIn(httpError);
                entries = AiErrorBodies.usageIn(httpError);
            }
            if (entries == null || usage.recordLines(USAGE_FEATURE, projectId, null, requestKey, entries, false, null, duration) == 0) {
                usage.recordFailure(USAGE_FEATURE, projectId, null, requestKey, duration);
            }
            if (timeout) {
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "ai_timeout", "작업 생성이 지연되고 있어요. 다시 시도해 주세요.");
            }
            if ("model_output_truncated".equals(aiErrorCode)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_output_too_long", "작업 목록이 길어 생성 도중 끊겼어요.");
            }
            if ("invalid_output".equals(aiErrorCode)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_invalid_output", "생성된 작업을 확인하지 못했어요.");
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "ai_unavailable", "작업을 생성하지 못했어요. 다시 시도해 주세요.");
        }

        List<TaskBreakdownDraft> result;
        try {
            result = TaskBreakdownDraft.readInDependencyOrder(response, designVersions, areas);
        } catch (ApiException e) {
            usage.recordFailure(USAGE_FEATURE, projectId, null, requestKey, elapsed(started));
            throw e;
        }
        if (usage.record(USAGE_FEATURE, projectId, null, requestKey, response, elapsed(started)) == 0) {
            usage.recordFailure(USAGE_FEATURE, projectId, null, requestKey, elapsed(started));
        }
        return result;
    }

    private TaskBreakdownResponse save(CallerContext caller, long projectId, long baselineId, List<TaskBreakdownDraft> drafts) {
        List<Member> members = loadMembers(projectId);
        Map<Long, Long> loads = members.stream().collect(Collectors.toMap(Member::id, Member::openTasks));
        jdbc.sql("insert into task_breakdown_batch(project_id, baseline_id) values (:project, :baseline)")
                .param("project", projectId).param("baseline", baselineId).update();
        Map<String, String> savedIds = new HashMap<>();
        int position = 0;
        for (TaskBreakdownDraft draft : drafts) {
            TaskDetailResponse task = plans.createTaskForBreakdown(caller, projectId, draft.toPlan(savedIds));
            long taskId = Long.parseLong(task.id());
            Member assigned = selectAssignee(draft.assignmentArea(), members, loads);
            if (assigned != null) {
                assignees.changeAssignee(caller, projectId, taskId, assigned.id(), task.lockVersion());
                loads.merge(assigned.id(), 1L, Long::sum);
            }
            jdbc.sql("insert into task_breakdown_task(project_id, task_id, position) values (:project, :task, :position)")
                    .param("project", projectId).param("task", taskId).param("position", ++position).update();
            savedIds.put(draft.key(), task.id());
        }
        return savedBatch(projectId);
    }

    private TaskBreakdownJobResponse response(Job job) {
        List<GeneratedTask> tasks = job.status() == Status.COMPLETED ? savedBatch(job.projectId()).tasks() : null;
        return response(job, tasks);
    }

    private static TaskBreakdownJobResponse response(Job job, List<GeneratedTask> tasks) {
        return new TaskBreakdownJobResponse(Long.toString(job.id()), Long.toString(job.baselineId()),
                job.status(), tasks, job.errorCode(), job.retryable());
    }

    private TaskBreakdownResponse savedBatch(long projectId) {
        record SavedTask(String baselineId, GeneratedTask task) { }
        List<SavedTask> saved = jdbc.sql("""
                select b.baseline_id, t.id, t.key, t.title, t.assignee_member_id
                  from task_breakdown_batch b
                  join task_breakdown_task generated on generated.project_id = b.project_id
                  join task t on t.id = generated.task_id and t.project_id = generated.project_id
                 where b.project_id = :project
                 order by generated.position
                """).param("project", projectId).query((rs, index) -> new SavedTask(rs.getString("baseline_id"),
                new GeneratedTask(rs.getString("id"), rs.getString("key"), rs.getString("title"), rs.getString("assignee_member_id")))).list();
        if (saved.isEmpty()) return null;
        return new TaskBreakdownResponse(saved.getFirst().baselineId(), saved.stream().map(SavedTask::task).toList());
    }

    private List<DesignItem> approvedDesigns(long projectId, long baselineId) {
        return jdbc.sql("""
                select d.id, v.id as version_id, v.title, d.kind, left(v.content::text, :excerpt) as content
                  from baseline_item b
                  join design_item d on d.id = b.design_item_id and d.project_id = b.project_id
                  join design_item_version v on v.id = b.design_item_version_id
                 where b.project_id = :project and b.baseline_id = :baseline and d.retired_at is null
                 order by d.id limit :limit
                """).param("project", projectId).param("baseline", baselineId).param("excerpt", MAX_EXCERPT)
                .param("limit", MAX_ITEMS + 1).query((rs, index) -> new DesignItem(rs.getString("id"), rs.getString("version_id"),
                        rs.getString("title"), rs.getString("kind"), rs.getString("content"))).list();
    }

    private List<ApprovedDocument> approvedDocuments(long projectId, long baselineId) {
        return jdbc.sql("""
                select concat(d.type, ' · ', d.title) as title, left(v.content_text, :excerpt) as content
                  from baseline_document b
                  join document d on d.id = b.document_id and d.project_id = b.project_id
                  join document_version v on v.id = b.document_version_id
                 where b.project_id = :project and b.baseline_id = :baseline and nullif(btrim(v.content_text), '') is not null
                 order by row_number() over (partition by d.type order by d.id), d.id
                 limit :limit
                """).param("project", projectId).param("baseline", baselineId).param("excerpt", MAX_EXCERPT)
                .param("limit", MAX_DOCUMENTS).query((rs, index) -> new ApprovedDocument(rs.getString("title"), rs.getString("content"))).list();
    }

    private List<Member> loadMembers(long projectId) {
        return jdbc.sql("""
                select m.id, m.area, m.role_label, coalesce(w.open_tasks, 0) as open_tasks
                  from project_member m join account a on a.id = m.account_id and a.deleted_at is null
                  left join (
                    select assignee_member_id, count(*) as open_tasks
                      from task where project_id = :project and status in ('DRAFT', 'READY', 'IN_PROGRESS', 'WAITING')
                     group by assignee_member_id
                  ) w on w.assignee_member_id = m.id
                 where m.project_id = :project and m.active
                 order by m.id
                """).param("project", projectId).query((rs, index) -> new Member(rs.getLong("id"),
                Stream.of(rs.getString("area"), rs.getString("role_label")).filter(Objects::nonNull).map(String::strip)
                        .filter(label -> !label.isEmpty() && label.codePointCount(0, label.length()) <= 100).distinct().toList(),
                rs.getLong("open_tasks"))).list();
    }

    private static Member selectAssignee(String area, List<Member> members, Map<Long, Long> loads) {
        if (area == null) return null;
        String label = area.toLowerCase(Locale.ROOT);
        return members.stream().filter(member -> member.areas().stream().anyMatch(value -> value.toLowerCase(Locale.ROOT).equals(label)))
                .min(Comparator.<Member>comparingLong(member -> loads.get(member.id())).thenComparingLong(Member::id)).orElse(null);
    }

    private static Map<String, Object> modelInput(long projectId, List<ApprovedDocument> documents,
                                                   List<DesignItem> items, List<String> areas) {
        int documentShare = documents.isEmpty() ? 0 : DOCUMENT_BUDGET / documents.size();
        List<Map<String, String>> documentInput = documents.stream().map(doc ->
                Map.of("title", excerpt(doc.title(), 300), "content", excerpt(doc.content(), documentShare))).toList();
        int documentLength = documentInput.stream().mapToInt(doc -> doc.get("content").length()).sum();
        int itemShare = (CONTEXT_BUDGET - documentLength) / items.size();
        List<Map<String, String>> itemInput = items.stream().map(item -> Map.of("design_item_id", item.id(),
                "design_item_version_id", item.versionId(), "title", excerpt(item.title(), 300), "kind", item.kind(),
                "content", excerpt(item.content(), itemShare))).toList();
        return Map.of("project_id", Long.toString(projectId), "documents", documentInput,
                "design_items", itemInput, "assignment_areas", areas);
    }

    private static String excerpt(String text, int share) {
        int limit = Math.min(MAX_EXCERPT, share);
        if (text.length() <= limit) return text;
        return text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit);
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static boolean interrupted(Throwable error) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedException) return true;
        }
        return false;
    }

    private record Snapshot(Map<String, Object> input, Map<String, String> designVersions, List<String> assignmentAreas) { }
    private record DesignItem(String id, String versionId, String title, String kind, String content) { }
    private record ApprovedDocument(String title, String content) { }
    private record Member(long id, List<String> areas, long openTasks) { }
}
