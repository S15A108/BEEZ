package com.beez.backend.task.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 뒤에 받는 쪽. 지금은 로그만. -64 에서 알림 요청·활동 이벤트 전달로 바꾼다.
 *
 * <p>{@code @TransactionalEventListener(AFTER_COMMIT)}: 상태 변경 트랜잭션이 성공적으로 끝난 뒤에만 실행된다.
 * 롤백된 변경에 대해 알림이 나가는 일을 막는다 (설계 D7 원칙과 같음).
 */
@Component
public class TaskStateChangedListener {

    private static final Logger log = LoggerFactory.getLogger(TaskStateChangedListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TaskStateChangedEvent e) {
        log.info("task state changed project={} task={} {} -> {} by event={} actor={} dedupe={}",
                e.projectId(), e.taskId(), e.from(), e.to(), e.event(), e.actorAccountId(), e.dedupeKey());
    }
}
