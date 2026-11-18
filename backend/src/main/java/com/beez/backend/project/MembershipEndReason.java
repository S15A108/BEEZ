package com.beez.backend.project;

/**
 * 멤버십이 끝난 이유 (project_member.left_reason, V11). 값과 철자는 DB CHECK 와 같아야 한다.
 * 재초대 규칙이 이걸로 갈린다 (Q05) — REMOVED 는 로그인·가입 때 되살리고, LEFT 는 메일 링크(MEM-A05)로만 되살린다.
 */
public enum MembershipEndReason {
    /** 팀장이 제외 (MEM-A09) */
    REMOVED,
    /** 스스로 나감 (MEM-A10) */
    LEFT
}
