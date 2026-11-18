package com.beez.backend.context.entity;

/** 공유 기록 종류 (DDL context_record.kind CHECK). DECISION 도 공유 판단일 뿐 승인 설계가 아니다 */
public enum ContextRecordKind {
    DECISION,
    PROGRESS,
    ISSUE,
    HANDOVER
}
