package com.beez.backend.task.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.dto.TaskPlanRevisionResponse;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskPlanDesignRef;
import com.beez.backend.task.entity.TaskPlanRevision;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.repository.TaskPlanDesignRefRepository;
import com.beez.backend.task.repository.TaskPlanRevisionRepository;
import com.beez.backend.task.repository.TaskRepository;
import com.beez.backend.task.repository.TaskSpecifications;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조회 (TSK-A02 목록, TSK-A03 상세, TSK-A05 리비전 목록) 와 TaskDetail 조립.
 * 읽기 전용 트랜잭션. 목록은 커서 방식: 커서 = 마지막 id, limit+1 개를 읽어 has_more 를 안다.
 */
@Service
@Transactional(readOnly = true)
public class TaskQueryService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final TaskRepository tasks;
    private final TaskPlanRevisionRepository revisions;
    private final TaskPlanDesignRefRepository designRefs;
    private final ProjectAccessChecker access;
    private final ValidationStatusLookup validationLookup;

    public TaskQueryService(TaskRepository tasks, TaskPlanRevisionRepository revisions,
                            TaskPlanDesignRefRepository designRefs, ProjectAccessChecker access,
                            ValidationStatusLookup validationLookup) {
        this.tasks = tasks;
        this.revisions = revisions;
        this.designRefs = designRefs;
        this.access = access;
        this.validationLookup = validationLookup;
    }

    public TaskDetailResponse getTask(CallerContext caller, Long projectId, Long taskId) {
        access.requireMember(caller, projectId);
        Task task = requireTask(projectId, taskId);
        return toDetail(task);
    }

    public PageResponse<TaskDetailResponse> listTasks(CallerContext caller, Long projectId, TaskStatus status,
                                                      String validationStatus, Long assigneeMemberId,
                                                      String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        int size = normalizeLimit(limit);
        Long cursorId = parseCursor(cursor);

        Specification<Task> spec = TaskSpecifications.projectIs(projectId)
                .and(TaskSpecifications.statusIs(status))
                .and(TaskSpecifications.assigneeIs(assigneeMemberId))
                .and(TaskSpecifications.idBefore(cursorId));
        // validation_status 필터도 페이지를 자르기 전에 건다 (명세 00-common 3절 "필터는 페이지·검색·집계 전")
        if (validationStatus != null) {
            if (!ValidationStatusLookup.ALL.contains(validationStatus)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed",
                        "validation_status 값이 올바르지 않습니다: " + validationStatus);
            }
            List<Long> matching = validationLookup.taskIdsWithStatus(projectId, validationStatus);
            if (matching.isEmpty()) {
                return PageResponse.of(new ArrayList<>(), false, null);
            }
            spec = spec.and(TaskSpecifications.idIn(matching));
        }
        // limit+1 개를 읽는다. 다 차면 다음 페이지가 있다는 뜻.
        List<Task> rows = tasks.findAll(spec, PageRequest.of(0, size + 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();

        boolean hasMore = rows.size() > size;
        List<Task> page;
        if (hasMore) {
            page = rows.subList(0, size);
        } else {
            page = rows;
        }
        List<TaskDetailResponse> items = toDetails(page);
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            nextCursor = String.valueOf(page.get(page.size() - 1).getId());
        }
        return PageResponse.of(items, hasMore, nextCursor);
    }

    public PageResponse<TaskPlanRevisionResponse> listRevisions(CallerContext caller, Long projectId, Long taskId,
                                                                String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        requireTask(projectId, taskId);
        int size = normalizeLimit(limit);
        Long cursorId = parseCursor(cursor);
        PageRequest pageable = PageRequest.of(0, size + 1);

        List<TaskPlanRevision> rows;
        if (cursorId == null) {
            rows = revisions.findByTaskIdOrderByIdDesc(taskId, pageable);
        } else {
            rows = revisions.findByTaskIdAndIdLessThanOrderByIdDesc(taskId, cursorId, pageable);
        }
        boolean hasMore = rows.size() > size;
        List<TaskPlanRevision> page;
        if (hasMore) {
            page = rows.subList(0, size);
        } else {
            page = rows;
        }

        Map<Long, List<TaskPlanDesignRef>> refsByRevision = loadRefs(page);
        List<TaskPlanRevisionResponse> items = new ArrayList<>();
        for (TaskPlanRevision r : page) {
            items.add(TaskPlanRevisionResponse.from(r, refsOf(refsByRevision, r.getId())));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            nextCursor = String.valueOf(page.get(page.size() - 1).getId());
        }
        return PageResponse.of(items, hasMore, nextCursor);
    }

    // ------------------------------------------------------------------
    // TaskDetail 조립 — 다른 서비스도 쓴다
    // ------------------------------------------------------------------

    public TaskDetailResponse toDetail(Task task) {
        TaskPlanRevisionResponse current = null;
        if (task.getCurrentPlanRevisionId() != null) {
            Optional<TaskPlanRevision> rev = revisions.findById(task.getCurrentPlanRevisionId());
            if (rev.isPresent()) {
                List<TaskPlanDesignRef> refs = designRefs.findByPlanRevisionId(rev.get().getId());
                current = TaskPlanRevisionResponse.from(rev.get(), refs);
            }
        }
        String validationStatus = validationLookup.latestStatus(task.getId(), task.getCurrentPlanRevisionId());
        return TaskDetailResponse.of(task, current, validationStatus);
    }

    /** 목록용: 리비전·참조·검증 상태를 각각 한 번씩만 조회한다 (N+1 방지) */
    private List<TaskDetailResponse> toDetails(List<Task> page) {
        List<Long> revisionIds = new ArrayList<>();
        List<Long> taskIds = new ArrayList<>();
        for (Task t : page) {
            taskIds.add(t.getId());
            if (t.getCurrentPlanRevisionId() != null) {
                revisionIds.add(t.getCurrentPlanRevisionId());
            }
        }
        Map<Long, TaskPlanRevision> revisionById = new HashMap<>();
        if (!revisionIds.isEmpty()) {
            for (TaskPlanRevision r : revisions.findByIdIn(revisionIds)) {
                revisionById.put(r.getId(), r);
            }
        }
        Map<Long, List<TaskPlanDesignRef>> refsByRevision = loadRefs(new ArrayList<>(revisionById.values()));
        Map<Long, String> statusByTask = validationLookup.latestStatusByTask(taskIds);

        List<TaskDetailResponse> result = new ArrayList<>();
        for (Task t : page) {
            TaskPlanRevisionResponse current = null;
            TaskPlanRevision r = revisionById.get(t.getCurrentPlanRevisionId());
            if (r != null) {
                current = TaskPlanRevisionResponse.from(r, refsOf(refsByRevision, r.getId()));
            }
            String vs = statusByTask.get(t.getId());
            if (vs == null) {
                vs = ValidationStatusLookup.NOT_RUN;
            }
            result.add(TaskDetailResponse.of(t, current, vs));
        }
        return result;
    }

    private Map<Long, List<TaskPlanDesignRef>> loadRefs(List<TaskPlanRevision> revs) {
        Map<Long, List<TaskPlanDesignRef>> byRevision = new HashMap<>();
        if (revs.isEmpty()) {
            return byRevision;
        }
        List<Long> ids = new ArrayList<>();
        for (TaskPlanRevision r : revs) {
            ids.add(r.getId());
        }
        for (TaskPlanDesignRef ref : designRefs.findByPlanRevisionIdIn(ids)) {
            List<TaskPlanDesignRef> list = byRevision.get(ref.getPlanRevisionId());
            if (list == null) {
                list = new ArrayList<>();
                byRevision.put(ref.getPlanRevisionId(), list);
            }
            list.add(ref);
        }
        return byRevision;
    }

    /** 리비전에 참조가 하나도 없으면 빈 목록 */
    private List<TaskPlanDesignRef> refsOf(Map<Long, List<TaskPlanDesignRef>> byRevision, Long revisionId) {
        if (byRevision.containsKey(revisionId)) {
            return byRevision.get(revisionId);
        }
        return new ArrayList<>();
    }

    private Task requireTask(Long projectId, Long taskId) {
        Optional<Task> task = tasks.findByIdAndProjectId(taskId, projectId);
        if (task.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        return task.get();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_LIMIT;
        }
        if (limit > MAX_LIMIT) {
            return MAX_LIMIT;
        }
        return limit;
    }

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "cursor 형식이 올바르지 않습니다");
        }
    }
}
