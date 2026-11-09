package com.beez.backend.task.event;

import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;
import com.beez.backend.task.entity.WaitingReason;

/**
 * 상태 보고(TSK-A07)로 작업 상태가 바뀌었다는 알림. 같은 트랜잭션의 커밋 직전에 활동으로 남는다 ({@link TaskStateChangedListener}).
 * 멱등 재전송처럼 상태가 바뀌지 않은 보고는 이 이벤트를 내지 않는다.
 *
 * @param caller        보고한 사람·에이전트. 계정 ID 만 담으면 같은 계정의 사람 행위와 에이전트 행위를 가를 수 없다(계약 2.1)
 * @param to            바뀐 뒤 상태
 * @param waitingReason 바뀐 뒤 대기 사유(WAITING 이 아니면 null). {@code to} 와 함께 활동 유형을 정한다(계약 6.2)
 * @param lockVersion   전이를 저장·flush 한 뒤의 잠금 버전. 활동 키 {@code task:{id}:lock:{n}:{event}} 의 {@code n}
 */
public record TaskStateChangedEvent(
        CallerContext caller,
        Long projectId,
        Long taskId,
        TaskStatus from,
        TaskStatus to,
        WaitingReason waitingReason,
        TaskEvent event,
        Integer lockVersion
) {
}
