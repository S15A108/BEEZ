package com.beez.backend.planning.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import org.springframework.stereotype.Component;

/** 팀장 전용 변경: 프로젝트 행을 잠근 뒤 현재 역할을 다시 확인한다(잠금을 기다리는 동안 팀장이 바뀔 수 있다). */
@Component
class LeaderGuard {
    private final ProjectAccessChecker access;
    private final DesignProjectRepository projects;

    LeaderGuard(ProjectAccessChecker access, DesignProjectRepository projects) {
        this.access = access; this.projects = projects;
    }

    void lock(CallerContext caller, long project) {
        access.requireHumanLeader(caller, project);
        if (!projects.lockActiveProject(project)) throw AccessErrors.notFound("planning_project_missing");
        access.requireHumanLeader(caller, project);
        String role = projects.findCurrentRole(project, caller.accountId())
                .orElseThrow(() -> AccessErrors.notFound("planning_membership_changed"));
        if (!"LEADER".equals(role)) throw AccessErrors.forbidden("planning_leader_changed", "현재 사람 팀장만 변경할 수 있습니다.");
    }
}
