package com.beez.backend.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋 뒤에 받는 쪽. 지금은 로그만. -135 에서 비비 초기화 호출로 바꾼다.
 * AFTER_COMMIT 이라 롤백된 생성에 대해 AI 를 부르는 일이 없다 (TaskStateChangedListener 와 같은 방식).
 */
@Component
public class ProjectCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(ProjectCreatedListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProjectCreatedEvent e) {
        log.info("project created project={} owner={}", e.projectId(), e.ownerAccountId());
    }
}
