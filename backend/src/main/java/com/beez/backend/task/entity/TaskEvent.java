package com.beez.backend.task.entity;

/** 상태 보고 이벤트. 이름은 명세 TSK-A07 의 event 값 그대로 (COMPLETE 가 아니라 COMPLETED). */
public enum TaskEvent {
    START,
    RESUME,
    WAIT,
    COMPLETED,
    CANCEL;

    /** 시작 게이트(4조건)를 지나야 하는 이벤트인가 */
    public boolean needsStartGate() {
        return this == START || this == RESUME;
    }
}
