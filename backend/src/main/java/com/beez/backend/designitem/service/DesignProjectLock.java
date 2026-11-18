package com.beez.backend.designitem.service;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.DesignProjectRepository;
import org.springframework.stereotype.Component;

/** Lock the project first, then recheck current authority in the same command transaction. */
@Component
class DesignProjectLock {
    private final DesignProjectRepository projects;
    private final ProjectAccessChecker access;

    DesignProjectLock(DesignProjectRepository projects, ProjectAccessChecker access) {
        this.projects = projects;
        this.access = access;
    }

    void leader(CallerContext caller, long project) {
        access.requireHumanLeader(caller, project);
        if (!projects.lockActiveProject(project)) {
            throw AccessErrors.notFound("design_project_missing");
        }
        access.requireHumanLeader(caller, project);
        // A membership loaded before waiting can remain in the JPA first-level cache.
        String role = projects.findCurrentRole(project, caller.accountId())
                .orElseThrow(() -> AccessErrors.notFound("design_membership_changed"));
        if (!"LEADER".equals(role)) {
            throw AccessErrors.forbidden("design_leader_changed", "현재 사람 팀장만 변경할 수 있습니다.");
        }
    }
}
