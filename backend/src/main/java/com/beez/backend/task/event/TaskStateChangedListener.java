package com.beez.backend.task.event;

import com.beez.backend.task.service.TaskActivities;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 상태 보고의 활동 기록 (S15P31A108-287, 계약 10-notification-activity-contract.md 2.1·6.2).
 *
 * <p>{@code BEFORE_COMMIT}: 상태를 바꾼 트랜잭션이 커밋되기 직전, 같은 트랜잭션 안에서 남긴다. 기록이 실패하면 전이도 함께 롤백되고,
 * 전이가 롤백되면 기록도 없다. {@code AFTER_COMMIT} 은 쓰지 않는다 — 커밋이 끝난 뒤라 저장이 반영되지 않을 수 있고 유실 틈이 남는다.
 * 롤백으로 끝나는 트랜잭션에서는 불리지 않는다.
 */
@Component
public class TaskStateChangedListener {

    private final TaskActivities activities;

    public TaskStateChangedListener(TaskActivities activities) {
        this.activities = activities;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TaskStateChangedEvent e) {
        activities.stateReported(e.caller(), e.projectId(), e.taskId(), e.event(), e.to(), e.waitingReason(), e.lockVersion());
    }
}
