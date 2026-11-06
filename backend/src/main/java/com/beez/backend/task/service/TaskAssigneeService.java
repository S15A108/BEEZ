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
 * TSK-A06 담당자 변경. 사람 세션만. 새 담당자는 같은 프로젝트의 활성 멤버.
 * <ul>
 *   <li>팀장은 어느 작업이든 누구에게나 (명세)</li>
 *   <li>팀원은 <b>담당자 없는(미배정) 작업을 자기에게</b> 맡기는 것만 (-301, 11-04 사용자 결정). 다른 사람 지정·담당자 있는 작업은 403</li>
 * </ul>
 * 판정 순서 (-301): 프로젝트 행 잠금 → 잠금 번호(409 revision_conflict) → 권한·미배정(403) → 완료·취소(400) → 새 담당자 활성(400).
 * 잠금 번호를 권한보다 먼저 보는 이유: 두 팀원이 같은 미배정 작업을 동시에 맡으면, 늦은 쪽은 작업이 이미 맡겨져 권한으로는 403 이지만
 * 실제 사정은 "다른 사람이 먼저 맡음" 이라 409 가 맞다 (화면 PR #140 의 "다른 팀원이 먼저 맡았어요").
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
        ProjectActor actor = access.requireMember(caller, projectId);
        actor.requireHuman();
        // 프로젝트 행을 먼저 잠그고 작업을 읽는다. 팀원 제외·나가기(MEM-A09·A10)·팀장 위임(MEM-A08)·상태 보고(START 등)·
        // 다른 사람의 담당자 변경이 같은 행을 잠그므로, 잠근 뒤에 요청자가 지금 팀장인지, 작업이 아직 미배정인지,
        // 작업이 진행 중인지, 새 담당자가 그 사이 나가지 않았는지를 본다
        if (!designLookup.lockProject(projectId)) {
            throw AccessErrors.notFound("project_missing project=" + projectId);
        }
        Optional<Task> found = tasks.findByIdAndProjectId(taskId, projectId);
        if (found.isEmpty()) {
            throw AccessErrors.notFound("task_not_in_project task=" + taskId + " project=" + projectId);
        }
        Task task = found.get();
        if (task.getLockVersion() != expectedLockVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict",
                    "작업 상태가 이미 바뀌었습니다 (현재 lock_version=" + task.getLockVersion() + ")");
        }
        if (!access.isLeaderAfterLock(actor)) {
            requireSelfClaimOfUnassigned(actor, task, newAssigneeMemberId);
        }
        if (task.getStatus().isClosed()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_transition", "완료·취소된 작업의 담당자는 바꿀 수 없습니다");
        }
        if (!access.isActiveMember(newAssigneeMemberId, projectId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "같은 프로젝트의 활성 멤버가 아닙니다");
        }
        // 저장(flush)·활동·알림은 reassign 이 한다 (-288). 스스로 맡기면 새 담당자가 본인이라 알림은 가지 않는다
        handover.reassign(caller, task, newAssigneeMemberId);
        return query.toDetail(task);
    }

    /** 팀장이 아니면: 담당자 없는 작업을 자기에게 맡기는 것만 된다. 아니면 403 */
    private static void requireSelfClaimOfUnassigned(ProjectActor actor, Task task, Long newAssigneeMemberId) {
        boolean toSelf = newAssigneeMemberId != null && newAssigneeMemberId == actor.memberId();
        if (task.getAssigneeMemberId() != null || !toSelf) {
            throw AccessErrors.forbidden("assignee_change_not_allowed task=" + task.getId() + " member=" + actor.memberId(),
                    "팀원은 담당자 없는 작업을 자기에게 맡기는 것만 할 수 있습니다");
        }
    }
}
