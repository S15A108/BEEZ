package com.beez.backend.task.event;

import com.beez.backend.task.service.TaskActivities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 상태 보고의 활동 기록 (S15P31A108-287, 계약 10-notification-activity-contract.md 2.1·6.2).
 *
 * <p>{@code BEFORE_COMMIT}: 상태를 바꾼 트랜잭션이 커밋되기 직전, 같은 트랜잭션 안에서 남긴다. 기록이 실패하면 전이도 함께 롤백되고,
 * 전이가 롤백되면 기록도 없다. {@code AFTER_COMMIT} 은 쓰지 않는다 — 커밋이 끝난 뒤라 저장이 반영되지 않을 수 있고 유실 틈이 남는다.
 * 롤백으로 끝나는 트랜잭션에서는 불리지 않는다.
 *
 * <p>전이 로그는 예전(AFTER_COMMIT 로그)과 같은 모양으로 남긴다 — 서버 로그로 전이를 추적하던 길을 유지한다. 활동에는 바뀌기 전 상태가
 * 없어 {@code from} 은 로그에만 나온다. 커밋 직전에 찍으므로, 그 뒤 커밋이 실패하면 로그만 남고 전이는 없다.
 */
@Component
public class TaskStateChangedListener {

    private static final Logger log = LoggerFactory.getLogger(TaskStateChangedListener.class);

    private final TaskActivities activities;

    public TaskStateChangedListener(TaskActivities activities) {
        this.activities = activities;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TaskStateChangedEvent e) {
        log.info("task state changed project={} task={} {} -> {} ({}) by event={} actor={}:{} lock={}",
                e.projectId(), e.taskId(), e.from(), e.to(), e.waitingReason(), e.event(),
                e.caller().kind(), e.caller().accountId(), e.lockVersion());
        activities.stateReported(e.caller(), e.projectId(), e.taskId(), e.event(), e.to(), e.waitingReason(), e.lockVersion());
    }
}
