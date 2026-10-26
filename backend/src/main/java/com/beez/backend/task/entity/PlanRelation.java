package com.beez.backend.task.entity;

/**
 * 계획이 설계 항목을 어떻게 다루는지. 센 순서: CHANGES > IMPLEMENTS > READS.
 * 같은 항목이 여러 관계로 오면 센 것 하나만 저장한다 (PK 가 항목당 1행).
 */
public enum PlanRelation {
    READS(1),
    IMPLEMENTS(2),
    CHANGES(3);

    private final int strength;

    PlanRelation(int strength) {
        this.strength = strength;
    }

    public boolean isStrongerThan(PlanRelation other) {
        return this.strength > other.strength;
    }
}
