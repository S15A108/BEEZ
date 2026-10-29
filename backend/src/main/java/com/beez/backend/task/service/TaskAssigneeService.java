package com.beez.backend.task.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.dto.TaskDetailResponse;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.repository.TaskRepository;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TSK-A06 담당자 변경. 사람 세션의 LEADER 만 (명세). 새 담당자는 같은 프로젝트의 활성 멤버.
 * 진행 중 작업의 인계·재검증 정책은 미결(04-task-lifecycle.md §6) — 지금은 막지 않고 바꾼다.
 */
@Service
public class TaskAssigneeService {

    private final TaskRepository tasks;
    private final ProjectAccessChecker access;
    private final TaskQueryService query;
    private final ProjectDesignLookup designLookup;

    public TaskAssigneeService(TaskRepository tasks, ProjectAccessChecker access, TaskQueryService query,
                               ProjectDesignLookup designLookup) {
        this.tasks = tasks;
        this.access = access;
        this.query = query;
        this.designLookup = designLookup;
    }

    @Transactional
    public TaskDetailResponse changeAssignee(CallerContext caller, Long projectId, Long taskId,
                                             Long newAssigneeMemberId, int expectedLockVersion) {
        access.requireHumanLeader(caller, projectId);
        Optional<Task> found = tasks.findByIdAndProjectId(taskId, projectId);
        if (found.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        Task task = found.get();
        if (task.getLockVersion() != expectedLockVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "작업 상태가 이미 바뀌었습니다 (현재 lock_version=" + task.getLockVersion() + ")");
        }
        if (task.getStatus().isClosed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition", "완료·취소된 작업의 담당자는 바꿀 수 없습니다");
        }
        // 팀원 제외·나가기(MEM-A09·A10)와 같은 프로젝트 행을 잠근 뒤 본다. 안 잠그면 제외와 겹쳤을 때
        // 확인은 통과하고 제외가 먼저 커밋돼, 나간 사람이 담당인 열린 작업이 남는다
        if (!designLookup.lockProject(projectId)) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        if (!access.isActiveMember(newAssigneeMemberId, projectId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "같은 프로젝트의 활성 멤버가 아닙니다");
        }
        task.changeAssignee(newAssigneeMemberId);
        tasks.saveAndFlush(task);
        return query.toDetail(task);
    }
}
