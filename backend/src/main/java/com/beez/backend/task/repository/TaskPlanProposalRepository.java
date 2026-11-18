package com.beez.backend.task.repository;

import com.beez.backend.task.dto.TaskPlanProposalResponse;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class TaskPlanProposalRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public TaskPlanProposalRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Proposal(long id, long accountId, long revisionId, int validationLockVersion,
                           TaskPlanProposalResponse content, Long appliedValidationId) {}

    public TaskPlanProposalResponse insert(long projectId, long taskId, long accountId, long revisionId,
                                           int validationLockVersion, TaskPlanProposalResponse proposal) {
        long id = jdbc.sql("""
                insert into task_plan_revision_proposal
                    (project_id, task_id, created_by_account_id, baseline_id, plan_revision_id,
                     expected_revision_no, expected_lock_version, source_validation_id,
                     source_validation_lock_version, content, expires_at)
                values (:p, :t, :a, :b, :r, :rn, :lv, :v, :vl, cast(:content as jsonb), :expires)
                returning id
                """).param("p", projectId).param("t", taskId).param("a", accountId)
                .param("b", Long.parseLong(proposal.baselineId())).param("r", revisionId)
                .param("rn", proposal.expectedRevisionNo()).param("lv", proposal.expectedLockVersion())
                .param("v", Long.parseLong(proposal.sourceValidationId())).param("vl", validationLockVersion)
                .param("content", json.writeValueAsString(proposal))
                .param("expires", java.sql.Timestamp.from(proposal.expiresAt())).query(Long.class).single();
        return withId(proposal, id);
    }

    public Optional<Proposal> lock(long projectId, long taskId, long id) {
        return jdbc.sql("""
                select id, created_by_account_id, plan_revision_id, source_validation_lock_version,
                       content::text, applied_validation_id
                  from task_plan_revision_proposal where project_id=:p and task_id=:t and id=:id for update
                """).param("p", projectId).param("t", taskId).param("id", id).query((rs, row) ->
                new Proposal(rs.getLong("id"), rs.getLong("created_by_account_id"), rs.getLong("plan_revision_id"),
                        rs.getInt("source_validation_lock_version"), withId(json.readValue(rs.getString("content"),
                        TaskPlanProposalResponse.class), rs.getLong("id")), rs.getObject("applied_validation_id", Long.class)))
                .optional();
    }

    public void markApplied(long id, long validationId) {
        jdbc.sql("update task_plan_revision_proposal set applied_validation_id=:v, applied_at=now() where id=:id")
                .param("v", validationId).param("id", id).update();
    }

    private static TaskPlanProposalResponse withId(TaskPlanProposalResponse p, long id) {
        return new TaskPlanProposalResponse(Long.toString(id), p.baselineId(), p.sourceValidationId(),
                p.expectedRevisionNo(), p.expectedLockVersion(), p.beforePlan(), p.proposedPlan(),
                p.changes(), p.evidence(), p.expiresAt());
    }
}
