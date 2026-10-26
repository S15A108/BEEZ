package com.beez.backend.task.entity;

/** 선행 관계 종류. BLOCKS 만 시작 게이트(선행 완료 검사)에 쓴다. RELATES 는 표시용. */
public enum DependencyKind {
    BLOCKS,
    RELATES
}
