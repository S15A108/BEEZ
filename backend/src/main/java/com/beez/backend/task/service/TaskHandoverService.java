package com.beez.backend.task.service;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.repository.TaskRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 팀원 제외·나가기(MEM-A09·A10)의 열린 작업 인계 (S15P31A108-212). 권한·멤버십 확인은 부르는 쪽(ProjectMemberService)이 끝낸다.
 * <p>
 * 열린 작업 = DRAFT·READY·IN_PROGRESS·WAITING. 점유 중(IN_PROGRESS·WAITING)인 작업은 떠난 사람의 검증을 쓰지 못하게 하고,
 * IN_PROGRESS 는 WAITING(REVALIDATION) 으로 내린 뒤 넘긴다 — 새 담당자는 RESUME 때 G1 에서 새 검증을 받는다
 * (04-task-lifecycle.md §6 "진행 중 담당자 변경: WAITING 으로 내린 뒤", 2026-10-29 전진 결정). WAITING 의 다른 사유는 덮어쓰지 않는다.
 * <p>
 * 검증 무효화는 진행 중 계획 변경(S2, {@code markStaleForTask})이 아니라 작업 취소와 같은 {@code invalidateAllForTask} 를 쓴다.
 * S2 는 계획 리비전이 바뀌어 도는 검증이 늦게 PASS 가 돼도 G1 이 거르지만, 인계는 리비전이 그대로라 RUNNING 까지 막아야 한다.
 */
@Service
public class TaskHandoverService {

    /** 검증을 무효로 만든 이유 (validation.stale_reason) */
    static final String STALE_REASON = "ASSIGNEE_CHANGED";

    private static final Set<TaskStatus> OPEN = EnumSet.of(
            TaskStatus.DRAFT, TaskStatus.READY, TaskStatus.IN_PROGRESS, TaskStatus.WAITING);

    private final TaskRepository tasks;
    private final ValidationStatusLookup validations;

    public TaskHandoverService(TaskRepository tasks, ValidationStatusLookup validations) {
        this.tasks = tasks;
        this.validations = validations;
    }

    /**
     * fromMemberId 의 열린 작업을 toMemberId 에게 넘긴다. 열린 작업이 있는데 toMemberId 가 없으면 409 active_tasks_exist.
     * 멤버십 종료와 같은 트랜잭션이어야 해서 바깥 트랜잭션을 요구한다.
     *
     * @param toMemberId 같은 프로젝트의 활성 멤버, fromMemberId 아님 (부르는 쪽이 확인). 열린 작업이 없으면 null 이어도 된다
     * @return 넘긴 작업 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int handOver(long projectId, long fromMemberId, Long toMemberId) {
        List<Task> open = tasks.findByProjectIdAndAssigneeMemberIdAndStatusIn(projectId, fromMemberId, OPEN);
        if (open.isEmpty()) {
            return 0;
        }
        if (toMemberId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "active_tasks_exist",
                    "정리되지 않은 열린 작업이 " + open.size() + "개 있습니다. 인계 대상을 지정하세요");
        }
        for (Task task : open) {
            if (task.getStatus().isOccupied()) {
                validations.invalidateAllForTask(task.getId(), STALE_REASON);
            }
            if (task.getStatus() == TaskStatus.IN_PROGRESS) {
                task.holdForRevalidation();
            }
            task.changeAssignee(toMemberId);
        }
        // 동시에 상태가 바뀐 작업이 있으면 lock_version 이 달라 409 revision_conflict 로 전체가 되돌아간다
        tasks.saveAllAndFlush(open);
        return open.size();
    }
}
