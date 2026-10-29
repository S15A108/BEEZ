package com.beez.backend.task.service;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectActor;
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
 * 진행 중 작업은 팀원 제외·나가기의 인계와 같은 규칙으로 넘긴다 ({@link TaskHandoverService#reassign}) —
 * IN_PROGRESS 는 WAITING(REVALIDATION) 으로 내리고 이전 담당자 쪽에서 요청한 검증은 쓸 수 없게 한다 (-212, 04-task-lifecycle.md §2.2 S5).
 */
@Service
public class TaskAssigneeService {

    private final TaskRepository tasks;
    private final ProjectAccessChecker access;
    private final TaskQueryService query;
    private final ProjectDesignLookup designLookup;
    private final TaskHandoverService handover;

    public TaskAssigneeService(TaskRepository tasks, ProjectAccessChecker access, TaskQueryService query,
                               ProjectDesignLookup designLookup, TaskHandoverService handover) {
        this.tasks = tasks;
        this.access = access;
        this.query = query;
        this.designLookup = designLookup;
        this.handover = handover;
    }

    @Transactional
    public TaskDetailResponse changeAssignee(CallerContext caller, Long projectId, Long taskId,
                                             Long newAssigneeMemberId, int expectedLockVersion) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        // 프로젝트 행을 먼저 잠그고 작업을 읽는다. 팀원 제외·나가기(MEM-A09·A10)·팀장 위임(MEM-A08)·상태 보고(START 등)가 같은 행을
        // 잠그므로, 잠근 뒤에 요청자가 아직 팀장인지, 작업이 진행 중인지, 새 담당자가 그 사이 나가지 않았는지를 본다
        if (!designLookup.lockProject(projectId)) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        access.requireLeaderAfterLock(actor);
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
        if (!access.isActiveMember(newAssigneeMemberId, projectId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "같은 프로젝트의 활성 멤버가 아닙니다");
        }
        handover.reassign(task, newAssigneeMemberId);
        tasks.saveAndFlush(task);
        return query.toDetail(task);
    }
}
