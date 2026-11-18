package com.beez.backend.project;

import com.beez.backend.account.Account;
import com.beez.backend.common.api.Ids;

/**
 * 명세 00-common Member. domain 은 project_member.area 다. 이름은 계정 표시 이름이고,
 * 담당 영역·라벨·색상은 계정이 아니라 프로젝트별 정보다 (MEM-A07 처리 규칙).
 */
public record MemberResponse(
        String id,
        String accountId,
        String name,
        ProjectRole role,
        String domain,
        String roleLabel,
        String color,
        boolean agentConnected
) {
    public static MemberResponse of(ProjectMember m, Account account, boolean agentConnected) {
        return new MemberResponse(Ids.str(m.getId()), Ids.str(m.getAccountId()), account.getDisplayName(), m.getRole(),
                m.getArea(), m.getRoleLabel(), m.getColor(), agentConnected);
    }
}
