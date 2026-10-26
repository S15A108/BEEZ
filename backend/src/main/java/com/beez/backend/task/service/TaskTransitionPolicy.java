package com.beez.backend.task.service;

import com.beez.backend.task.entity.TaskEvent;
import com.beez.backend.task.entity.TaskStatus;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 상태 전이 표 (docs/design/04-task-lifecycle.md §2). DB 없이 순수 자바.
 *
 * <p>결과를 세 가지로 돌려주고 예외는 던지지 않는다 — 표 전체를 단위 테스트로 전수 검사하기 쉽게.
 * <ul>
 *   <li>ALLOWED   : 전이한다 (START/RESUME 는 시작 게이트를 추가로 지난다)</li>
 *   <li>IDEMPOTENT: 이미 그 상태. 바꾸지 않고 현재 상태를 200 으로 돌려준다</li>
 *   <li>FORBIDDEN : 400 invalid_transition</li>
 * </ul>
 */
@Component
public class TaskTransitionPolicy {

    public enum Verdict {
        ALLOWED,
        IDEMPOTENT,
        FORBIDDEN
    }

    /** 현재 상태 → 허용 이벤트 */
    private static final Map<TaskStatus, Set<TaskEvent>> ALLOWED = new EnumMap<>(TaskStatus.class);

    /** 현재 상태 → 멱등 이벤트 (같은 상태로 다시 보고) */
    private static final Map<TaskStatus, Set<TaskEvent>> IDEMPOTENT = new EnumMap<>(TaskStatus.class);

    static {
        ALLOWED.put(TaskStatus.DRAFT, EnumSet.of(TaskEvent.CANCEL));
        ALLOWED.put(TaskStatus.READY, EnumSet.of(TaskEvent.START, TaskEvent.CANCEL));
        ALLOWED.put(TaskStatus.IN_PROGRESS, EnumSet.of(TaskEvent.WAIT, TaskEvent.COMPLETED, TaskEvent.CANCEL));
        ALLOWED.put(TaskStatus.WAITING, EnumSet.of(TaskEvent.RESUME, TaskEvent.CANCEL));
        ALLOWED.put(TaskStatus.COMPLETED, EnumSet.noneOf(TaskEvent.class));
        ALLOWED.put(TaskStatus.CANCELLED, EnumSet.noneOf(TaskEvent.class));

        IDEMPOTENT.put(TaskStatus.DRAFT, EnumSet.noneOf(TaskEvent.class));
        IDEMPOTENT.put(TaskStatus.READY, EnumSet.noneOf(TaskEvent.class));
        IDEMPOTENT.put(TaskStatus.IN_PROGRESS, EnumSet.of(TaskEvent.START));
        IDEMPOTENT.put(TaskStatus.WAITING, EnumSet.noneOf(TaskEvent.class));
        IDEMPOTENT.put(TaskStatus.COMPLETED, EnumSet.of(TaskEvent.COMPLETED));
        IDEMPOTENT.put(TaskStatus.CANCELLED, EnumSet.of(TaskEvent.CANCEL));
    }

    public Verdict check(TaskStatus current, TaskEvent event) {
        if (IDEMPOTENT.get(current).contains(event)) {
            return Verdict.IDEMPOTENT;
        }
        if (ALLOWED.get(current).contains(event)) {
            return Verdict.ALLOWED;
        }
        return Verdict.FORBIDDEN;
    }
}
