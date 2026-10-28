package com.beez.backend.designitem;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Shared with the existing document/task protocol: lock project first, then recheck authority. */
@Component
class DesignProjectLock {
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;
    DesignProjectLock(JdbcClient jdbc, ProjectAccessChecker access) { this.jdbc = jdbc; this.access = access; }
    void leader(CallerContext caller, long project) {
        access.requireHumanLeader(caller, project);
        if (jdbc.sql("SELECT id FROM project WHERE id=:project AND archived_at IS NULL FOR UPDATE")
                .param("project", project).query(Long.class).optional().isEmpty()) {
            throw AccessErrors.notFound("design_project_missing");
        }
        access.requireHumanLeader(caller, project);
        // A JPA membership loaded before waiting can remain in the first-level cache after a role change.
        // Check the post-lock role as a scalar DB value instead of trusting that cached entity.
        String role = jdbc.sql("""
                SELECT m.role FROM project_member m JOIN account a ON a.id=m.account_id
                 WHERE m.project_id=:project AND m.account_id=:account AND m.active=true AND a.deleted_at IS NULL
                """).param("project", project).param("account", caller.accountId()).query(String.class).optional()
                .orElseThrow(() -> AccessErrors.notFound("design_membership_changed"));
        if (!"LEADER".equals(role)) throw AccessErrors.forbidden("design_leader_changed", "현재 사람 팀장만 변경할 수 있습니다.");
    }
}
