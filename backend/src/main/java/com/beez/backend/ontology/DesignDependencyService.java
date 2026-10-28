package com.beez.backend.ontology;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Version-bound, human-confirmed design relationships. Not an HTTP endpoint.
 * Existing EXPLICIT/AI_CANDIDATE rows are not copied or treated as confirmed.
 * Absence from this store does not establish that no relationship exists.
 */
@Service
public class DesignDependencyService {
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;

    public DesignDependencyService(JdbcClient jdbc, ProjectAccessChecker access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    /** The human leader explicitly confirms a directional relationship on the current baseline. */
    @Transactional
    public long confirm(CallerContext caller, long projectId, long baselineId,
                        long fromItemId, long toItemId, String reason) {
        return append(caller, projectId, baselineId, fromItemId, toItemId, reason, "CONFIRM");
    }

    /** Revoke a confirmed relationship without modifying its history. */
    @Transactional
    public long revoke(CallerContext caller, long projectId, long baselineId,
                       long fromItemId, long toItemId, String reason) {
        return append(caller, projectId, baselineId, fromItemId, toItemId, reason, "REVOKE");
    }

    private long append(CallerContext caller, long projectId, long baselineId,
                        long fromItemId, long toItemId, String reason, String action) {
        ProjectActor actor = access.requireHumanLeader(caller, projectId);
        if (fromItemId == toItemId || reason == null || reason.isBlank() || reason.strip().length() > 2000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "관계와 확인 사유를 확인해 주세요");
        }
        // Publication and project deletion lock this same project row. Read archive state
        // again after acquiring the lock: the initial permission check may have waited.
        LockedProject project = jdbc.sql("""
                select current_baseline_id, archived_at from project where id = :p for update
                """)
                .param("p", projectId).query((rs, row) -> new LockedProject(
                        (Long) rs.getObject("current_baseline_id"), rs.getTimestamp("archived_at") != null))
                .optional().orElseThrow(() -> AccessErrors.notFound("project_missing project=" + projectId));
        if (project.archived()) {
            throw AccessErrors.notFound("project_archived project=" + projectId);
        }
        // Do not call the JPA access checker a second time: the first query may leave a
        // stale entity in its persistence context. Lock and re-read the membership and
        // account with JDBC so role, active state and account deletion cannot change
        // before this event commits. Lock order: project -> membership/account.
        LockedMembership membership = jdbc.sql("""
                select m.role, m.active, a.deleted_at
                  from project_member m join account a on a.id = m.account_id
                 where m.project_id = :p and m.account_id = :a
                 for update of m, a
                """)
                .param("p", projectId).param("a", actor.accountId())
                .query((rs, row) -> new LockedMembership(rs.getString("role"), rs.getBoolean("active"),
                        rs.getTimestamp("deleted_at") != null))
                .optional().orElseThrow(() -> AccessErrors.notFound("membership_missing project=" + projectId));
        if (membership.accountDeleted()) {
            throw AccessErrors.unauthenticated();
        }
        if (!membership.active()) {
            throw AccessErrors.notFound("membership_inactive project=" + projectId);
        }
        if (!"LEADER".equals(membership.role())) {
            throw AccessErrors.forbidden("role_required_leader project=" + projectId, "팀장만 할 수 있습니다");
        }
        requireCurrent(project.baselineId(), baselineId);

        List<ItemVersion> versions = jdbc.sql("""
                select design_item_id, design_item_version_id from baseline_item
                 where project_id = :p and baseline_id = :b and design_item_id in (:from, :to)
                """)
                .param("p", projectId).param("b", baselineId)
                .param("from", fromItemId).param("to", toItemId)
                .query((rs, row) -> new ItemVersion(rs.getLong("design_item_id"),
                        rs.getLong("design_item_version_id"))).list();
        if (versions.size() != 2) {
            throw new ApiException(HttpStatus.NOT_FOUND, "not_found", "현재 기준의 설계 항목을 찾을 수 없습니다");
        }
        long fromVersion = versions.stream().filter(v -> v.itemId() == fromItemId).findFirst().orElseThrow().versionId();
        long toVersion = versions.stream().filter(v -> v.itemId() == toItemId).findFirst().orElseThrow().versionId();

        String previous = jdbc.sql("""
                select action from design_dependency_confirmation_event
                 where project_id = :p and baseline_id = :b and from_item_id = :f and to_item_id = :t
                 order by id desc limit 1
                """)
                .param("p", projectId).param("b", baselineId).param("f", fromItemId).param("t", toItemId)
                .query(String.class).optional().orElse(null);
        if ("REVOKE".equals(action) && !"CONFIRM".equals(previous)) {
            throw new ApiException(HttpStatus.CONFLICT, "dependency_not_confirmed", "확인된 관계가 없습니다");
        }
        return jdbc.sql("""
                insert into design_dependency_confirmation_event
                    (project_id, baseline_id, from_item_id, from_version_id, to_item_id, to_version_id,
                     action, reason, actor_account_id)
                values (:p, :b, :f, :fv, :t, :tv, :a, :r, :actor)
                returning id
                """)
                .param("p", projectId).param("b", baselineId).param("f", fromItemId).param("fv", fromVersion)
                .param("t", toItemId).param("tv", toVersion).param("a", action)
                .param("r", reason.strip()).param("actor", actor.accountId()).query(Long.class).single();
    }

    /**
     * Relationships confirmed for the current baseline only. The caller must regard the
     * returned graph as incomplete: absence does not imply no effect or no dependency.
     */
    @Transactional(readOnly = true)
    public List<ConfirmedDependency> listConfirmed(CallerContext caller, long projectId, long baselineId) {
        access.requireMember(caller, projectId);
        Long current = jdbc.sql("select current_baseline_id from project where id = :p")
                .param("p", projectId).query(Long.class).optional().orElse(null);
        requireCurrent(current, baselineId);
        List<ConfirmedDependency> result = jdbc.sql("""
                select * from (
                    select distinct on (e.from_item_id, e.to_item_id) e.*
                      from design_dependency_confirmation_event e
                      join project p on p.id = e.project_id and p.current_baseline_id = e.baseline_id
                     where e.project_id = :p and e.baseline_id = :b
                     order by e.from_item_id, e.to_item_id, e.id desc
                ) latest
                where action = 'CONFIRM'
                order by from_item_id, to_item_id
                """)
                .param("p", projectId).param("b", baselineId)
                .query((rs, row) -> new ConfirmedDependency(
                        rs.getLong("id"), rs.getLong("baseline_id"),
                        rs.getLong("from_item_id"), rs.getLong("from_version_id"),
                        rs.getLong("to_item_id"), rs.getLong("to_version_id"),
                        rs.getLong("actor_account_id"), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("reason")))
                .list();
        // A publication between the two reads must not turn a stale graph into an empty one.
        Long after = jdbc.sql("select current_baseline_id from project where id = :p")
                .param("p", projectId).query(Long.class).optional().orElse(null);
        requireCurrent(after, baselineId);
        return result;
    }

    private static void requireCurrent(Long current, long expected) {
        if (current == null) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 없습니다");
        }
        if (current != expected) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_changed", "기준 설계가 변경되어 재확인이 필요합니다");
        }
    }

    private record ItemVersion(long itemId, long versionId) {
    }

    private record LockedProject(Long baselineId, boolean archived) {
    }

    private record LockedMembership(String role, boolean active, boolean accountDeleted) {
    }
}
