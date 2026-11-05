package com.beez.backend.project;

import com.beez.backend.common.api.Ids;

/** 00-common BlockedProject. 회원 탈퇴를 막는 프로젝트 (ACC-A10 error.details·ACC-A13). */
public record BlockedProjectResponse(String projectId, String name, ProjectRole myRole, long memberCount) {

    public static BlockedProjectResponse of(WithdrawalMembershipRow row) {
        return new BlockedProjectResponse(Ids.str(row.projectId()), row.projectName(), row.role(),
                row.otherActiveMembers());
    }
}
