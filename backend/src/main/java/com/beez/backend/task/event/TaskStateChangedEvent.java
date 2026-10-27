package com.beez.backend.task.event;

import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;

/**
 * 작업 상태가 바뀌었다는 알림. 트랜잭션 커밋 뒤에만 전달된다 (TaskStateChangedListener).
 * 알림(S15P31A108-71)·타임라인(-72, 전진)과 연결은 -64 에서. 지금은 로그만 남긴다.
 *
 * @param dedupeKey 중복 식별값. 같은 전이를 두 번 처리하지 않게 (NFR-04). "task:{id}:lock:{n}:{event}"
 */
public record TaskStateChangedEvent(
        Long projectId,
        Long taskId,
        TaskStatus from,
        TaskStatus to,
        TaskEvent event,
        Long actorAccountId,
        String dedupeKey
) {
}
