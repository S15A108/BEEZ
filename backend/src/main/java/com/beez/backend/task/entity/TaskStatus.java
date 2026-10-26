package com.beez.backend.task.entity;

/**
 * 작업 상태. 값과 철자는 DB CHECK(V2__init_schema.sql task.status)와 같아야 한다.
 * 흐름: DRAFT → READY → IN_PROGRESS ⇄ WAITING → COMPLETED, 어디서든 CANCELLED. 자세한 규칙은 docs/design/04-task-lifecycle.md.
 */
public enum TaskStatus {
    DRAFT,
    READY,
    IN_PROGRESS,
    WAITING,
    COMPLETED,
    CANCELLED;

    /** 더 이상 계획을 바꿀 수 없는 상태인가 */
    public boolean isClosed() {
        return this == COMPLETED || this == CANCELLED;
    }

    /** 에이전트가 점유하고 있는 상태인가 (진행 중이거나 진행 중 멈춤) */
    public boolean isOccupied() {
        return this == IN_PROGRESS || this == WAITING;
    }
}
