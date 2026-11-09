package com.beez.backend.task.service;

import com.beez.backend.activity.ActivityEvent;
import com.beez.backend.activity.ActivityRecorder;
import com.beez.backend.activity.ActivityTargetType;
import com.beez.backend.activity.SystemActor;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.entity.Task;
import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 활동 (S15P31A108-287, 계약 docs/design/10-notification-activity-contract.md 6.2 "작업 유형 정하기"). 유형 상수와 키를 여기 모아
 * 생성(TSK-A01)·계획 변경(TSK-A04)·상태 보고(TSK-A07)·delta 검증 통과(S4)가 같은 규칙으로 남긴다. 담당자 변경은
 * {@link TaskHandoverService} 가 따로 남긴다(키가 변경 전 잠금 버전이라 규칙이 다르다, -288).
 *
 * <p><b>부르는 때.</b> 작업을 저장·flush 한 <b>뒤</b>, 같은 트랜잭션 안에서. 키에 flush 뒤 잠금 버전({@code @Version})을 쓰므로 flush 전에
 * 부르면 다음 변경과 키가 겹친다. 먼저 flush 해 두면 잠금 충돌이 도메인 자리에서 409 로 난다(입구 auto-flush 에서 나면 500, -288).
 *
 * <p><b>키.</b> {@code task:{id}:created}, 그 밖은 {@code task:{id}:lock:{flush 뒤 잠금 버전}:{동작}}. 상태 보고는 동작에 보고한
 * {@code TaskEvent} 이름을 쓴다(유형이 아니라 — delta 있는 COMPLETED 보고도 {@code :COMPLETED}). 작업 행이 바뀔 때마다 잠금 버전이
 * 오르므로 같은 작업의 다음 활동과 겹치지 않고, 같은 변경을 다시 처리하면 같은 키라 한 건만 남는다.
 */
@Component
public class TaskActivities {

    static final String TASK_CREATED = "TASK_CREATED";
    static final String TASK_PLAN_REVISED = "TASK_PLAN_REVISED";
    static final String TASK_STARTED = "TASK_STARTED";
    static final String TASK_RESUMED = "TASK_RESUMED";
    static final String TASK_WAITING = "TASK_WAITING";
    static final String TASK_COMPLETION_REPORTED = "TASK_COMPLETION_REPORTED";
    static final String TASK_COMPLETED = "TASK_COMPLETED";
    static final String TASK_CANCELLED = "TASK_CANCELLED";

    private final ActivityRecorder activities;

    public TaskActivities(ActivityRecorder activities) {
        this.activities = activities;
    }

    /** TSK-A01. {@code task:{id}:created} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void created(CallerContext caller, Task task) {
        activities.record(caller, ActivityEvent.of("task:" + task.getId() + ":created", task.getProjectId(), TASK_CREATED,
                ActivityTargetType.TASK, task.getId()));
    }

    /** TSK-A04. 진행 중 계획 변경으로 재검증 대기가 된 경우(S2)도 이 한 건이 대신한다. {@code task:{id}:lock:{n}:PLAN_REVISED} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void planRevised(CallerContext caller, Task task) {
        activities.record(caller, event(task.getProjectId(), task.getId(), task.getLockVersion(), "PLAN_REVISED", TASK_PLAN_REVISED));
    }

    /**
     * TSK-A07 상태 보고. 유형은 보고 이름이 아니라 실제 전이(바뀐 뒤 상태·대기 사유)로 정한다 — delta 있는 COMPLETED 는 작업을
     * WAITING(REVALIDATION) 으로 보내므로 완료가 아니라 {@code TASK_COMPLETION_REPORTED} 다. 멱등 재전송은 이벤트가 나가지 않아 여기 오지 않는다.
     *
     * @param lockVersion 전이를 저장·flush 한 뒤의 잠금 버전
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void stateReported(CallerContext caller, long projectId, long taskId, TaskEvent reported, TaskStatus to,
                              WaitingReason waitingReason, int lockVersion) {
        activities.record(caller, event(projectId, taskId, lockVersion, reported.name(), typeOf(reported, to, waitingReason)));
    }

    /** S4 — delta 검증 통과로 서버가 작업을 COMPLETED 로 바꿈. 행위자는 시스템. {@code task:{id}:lock:{m}:COMPLETED} (m 은 완료 보고 때보다 크다) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void completedBySystem(Task task) {
        activities.recordBy(SystemActor.SYSTEM,
                event(task.getProjectId(), task.getId(), task.getLockVersion(), TaskEvent.COMPLETED.name(), TASK_COMPLETED));
    }

    /** 계약 6.2 표. 상태 보고로 생길 수 없는 조합은 호출 코드 버그라 예외(도메인 처리도 롤백, 계약 2.2) */
    static String typeOf(TaskEvent reported, TaskStatus to, WaitingReason waitingReason) {
        return switch (to) {
            case IN_PROGRESS -> reported == TaskEvent.RESUME ? TASK_RESUMED : TASK_STARTED;
            // REVALIDATION 은 사람·에이전트가 WAIT 로 넣을 수 없다(시스템 전용) — 보고로 이 사유가 되는 길은 delta 있는 완료 보고뿐
            case WAITING -> waitingReason == WaitingReason.REVALIDATION ? TASK_COMPLETION_REPORTED : TASK_WAITING;
            case COMPLETED -> TASK_COMPLETED;
            case CANCELLED -> TASK_CANCELLED;
            default -> throw new IllegalStateException("no task activity for " + reported + " -> " + to);
        };
    }

    private static ActivityEvent event(long projectId, long taskId, int lockVersion, String action, String type) {
        return ActivityEvent.of("task:" + taskId + ":lock:" + lockVersion + ":" + action, projectId, type,
                ActivityTargetType.TASK, taskId);
    }
}
