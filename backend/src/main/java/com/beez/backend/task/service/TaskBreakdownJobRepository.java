package com.beez.backend.task.service;

import com.beez.backend.task.dto.TaskBreakdownJobResponse.Status;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class TaskBreakdownJobRepository {
    private final JdbcClient jdbc;

    TaskBreakdownJobRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Job> latest(long projectId) {
        return jdbc.sql("""
                select id, project_id, baseline_id, status, error_code, retryable
                  from task_breakdown_job where project_id = :project order by id desc limit 1
                """).param("project", projectId).query(TaskBreakdownJobRepository::job).optional();
    }

    Job enqueue(long projectId, long baselineId, long accountId, String snapshot) {
        return jdbc.sql("""
                insert into task_breakdown_job(project_id, baseline_id, requested_by, status, input_snapshot)
                values (:project, :baseline, :account, 'PENDING', cast(:snapshot as jsonb))
                returning id, project_id, baseline_id, status, error_code, retryable
                """).param("project", projectId).param("baseline", baselineId).param("account", accountId)
                .param("snapshot", snapshot).query(TaskBreakdownJobRepository::job).single();
    }

    Job recordExistingBatch(long projectId, long baselineId) {
        return jdbc.sql("""
                insert into task_breakdown_job(project_id, baseline_id, status)
                values (:project, :baseline, 'COMPLETED')
                returning id, project_id, baseline_id, status, error_code, retryable
                """).param("project", projectId).param("baseline", baselineId)
                .query(TaskBreakdownJobRepository::job).single();
    }

    Optional<Claim> claim(int leaseSeconds, int maxAttempts) {
        jdbc.sql("""
                with expired as (
                    select id from task_breakdown_job
                     where status = 'RUNNING' and lease_until <= now() and attempts >= :maxAttempts
                     order by id limit 25 for update skip locked
                )
                update task_breakdown_job j
                   set status = 'FAILED', error_code = 'ai_timeout', retryable = true,
                       input_snapshot = null, lease_token = null, lease_until = null, updated_at = now()
                  from expired where j.id = expired.id
                """).param("maxAttempts", maxAttempts).update();
        return jdbc.sql("""
                with next_job as (
                    select id from task_breakdown_job
                     where status = 'PENDING'
                        or (status = 'RUNNING' and lease_until <= now() and attempts < :maxAttempts)
                     order by id limit 1 for update skip locked
                )
                update task_breakdown_job j
                   set status = 'RUNNING', attempts = j.attempts + 1, lease_token = :token,
                       lease_until = now() + make_interval(secs => :leaseSeconds), updated_at = now()
                  from next_job where j.id = next_job.id
                returning j.id, j.project_id, j.baseline_id, j.status, j.error_code, j.retryable,
                          j.requested_by, j.input_snapshot::text, j.lease_token
                """).param("maxAttempts", maxAttempts).param("token", UUID.randomUUID())
                .param("leaseSeconds", leaseSeconds)
                .query((rs, row) -> new Claim(job(rs, row), rs.getLong("requested_by"),
                        rs.getString("input_snapshot"), rs.getObject("lease_token", UUID.class))).optional();
    }

    boolean holdLease(Claim claim) {
        return jdbc.sql("""
                select id from task_breakdown_job
                 where id = :id and status = 'RUNNING' and lease_token = :token and lease_until > clock_timestamp()
                   for update
                """).param("id", claim.job().id()).param("token", claim.leaseToken()).query(Long.class)
                .optional().isPresent();
    }

    void complete(Claim claim, long baselineId) {
        int updated = jdbc.sql("""
                update task_breakdown_job
                   set status = 'COMPLETED', baseline_id = :baseline, input_snapshot = null,
                       lease_token = null, lease_until = null, updated_at = now()
                 where id = :id and status = 'RUNNING' and lease_token = :token
                """).param("baseline", baselineId).param("id", claim.job().id())
                .param("token", claim.leaseToken()).update();
        if (updated != 1) throw new IllegalStateException("Task breakdown job lost its lease before completion");
    }

    void fail(Claim claim, String errorCode) {
        jdbc.sql("""
                update task_breakdown_job
                   set status = 'FAILED', error_code = :error, retryable = true, input_snapshot = null,
                       lease_token = null, lease_until = null, updated_at = now()
                 where id = :id and status = 'RUNNING' and lease_token = :token and lease_until > clock_timestamp()
                """).param("error", errorCode).param("id", claim.job().id()).param("token", claim.leaseToken()).update();
    }

    private static Job job(ResultSet rs, int row) throws SQLException {
        return new Job(rs.getLong("id"), rs.getLong("project_id"), rs.getLong("baseline_id"),
                Status.valueOf(rs.getString("status")), rs.getString("error_code"), rs.getObject("retryable", Boolean.class));
    }

    record Job(long id, long projectId, long baselineId, Status status, String errorCode, Boolean retryable) { }
    record Claim(Job job, long requestedBy, String inputSnapshot, UUID leaseToken) { }
}
