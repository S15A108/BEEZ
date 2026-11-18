package com.beez.backend.relation.repository;

/**
 * 관계 행의 확인자·시각을 어떻게 할지.
 * SET = 이 계정·지금으로 바꿈(확정·유지), KEEP = 그대로 둠(해제), CLEAR = 비움(후보 추가·거절).
 */
public record Confirmer(Mode mode, Long accountId) {

    public enum Mode { SET, KEEP, CLEAR }

    public static Confirmer set(long accountId) {
        return new Confirmer(Mode.SET, accountId);
    }

    public static Confirmer keep() {
        return new Confirmer(Mode.KEEP, null);
    }

    public static Confirmer clear() {
        return new Confirmer(Mode.CLEAR, null);
    }
}
