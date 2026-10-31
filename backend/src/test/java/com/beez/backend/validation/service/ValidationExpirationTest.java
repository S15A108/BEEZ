package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class ValidationExpirationTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final ObjectMapper json = mock(ObjectMapper.class);
    private final JdbcClient.StatementSpec identity = statement();
    private final JdbcClient.StatementSpec project = statement();
    private final JdbcClient.StatementSpec task = statement();
    private final JdbcClient.StatementSpec validation = statement();
    private final JdbcClient.StatementSpec run = statement();
    private final JdbcClient.StatementSpec job = statement();
    private final JdbcClient.StatementSpec write = statement();
    private final JdbcClient.ResultQuerySpec identityResult = mock(JdbcClient.ResultQuerySpec.class);
    private final JdbcClient.ResultQuerySpec projectResult = mock(JdbcClient.ResultQuerySpec.class);
    private final JdbcClient.ResultQuerySpec taskResult = mock(JdbcClient.ResultQuerySpec.class);
    private final JdbcClient.ResultQuerySpec validationResult = mock(JdbcClient.ResultQuerySpec.class);
    private final JdbcClient.ResultQuerySpec runResult = mock(JdbcClient.ResultQuerySpec.class);
    private final JdbcClient.ResultQuerySpec jobResult = mock(JdbcClient.ResultQuerySpec.class);
    @SuppressWarnings("unchecked")
    private final JdbcClient.MappedQuerySpec<Long> insertedRun = mock(JdbcClient.MappedQuerySpec.class);
    private final ValidationOutcomeWriter writer = new ValidationOutcomeWriter(jdbc, json, new ValidationRuleChecker(jdbc, json), null, null);
    private final UUID lease = UUID.randomUUID();

    private JdbcClient.StatementSpec statement() {
        return mock(JdbcClient.StatementSpec.class, invocation ->
                invocation.getMethod().getName().equals("param") ? invocation.getMock() : RETURNS_DEFAULTS.answer(invocation));
    }

    @BeforeEach void setup() {
        when(jdbc.sql(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            if (sql.contains("select t.project_id,v.task_id,j.lease_token")) return identity;
            if (sql.startsWith("select id from project")) return project;
            if (sql.startsWith("select id from task")) return task;
            if (sql.startsWith("select status,current_attempt from validation")) return validation;
            if (sql.startsWith("select id,attempt,status,kind,lease_token from validation_run")) return run;
            if (sql.startsWith("select lease_token from validation_job")) return job;
            return write;
        });
        when(identity.query()).thenReturn(identityResult);
        when(project.query()).thenReturn(projectResult);
        when(task.query()).thenReturn(taskResult);
        when(validation.query()).thenReturn(validationResult);
        when(run.query()).thenReturn(runResult);
        when(job.query()).thenReturn(jobResult);
        when(identityResult.listOfRows()).thenReturn(List.of(Map.of("project_id", 1L, "task_id", 2L, "lease_token", lease)));
        when(projectResult.listOfRows()).thenReturn(List.of(Map.of("id", 1L)));
        when(taskResult.listOfRows()).thenReturn(List.of(Map.of("id", 2L)));
        when(jobResult.listOfRows()).thenReturn(List.of(Map.of("lease_token", lease)));
        when(json.writeValueAsString(any())).thenReturn("{}");
        when(write.update()).thenReturn(1);
        when(write.query(Long.class)).thenReturn(insertedRun);
        when(insertedRun.single()).thenReturn(4L);
    }

    private void expired(String validationStatus, int currentAttempt, int runAttempt, String runStatus) {
        when(validationResult.listOfRows()).thenReturn(List.of(Map.of(
                "status", validationStatus, "current_attempt", currentAttempt)));
        when(runResult.listOfRows()).thenReturn(List.of(Map.of("id", 4L, "attempt", runAttempt,
                "status", runStatus, "kind", "MODEL", "lease_token", lease)));
    }

    @Test void staleValidationCancelsOldJobAndDiscardsOldRunOnly() {
        expired("STALE", 1, 1, "RUNNING");
        assertThat(writer.expire(3)).isTrue();
        verify(jdbc).sql(startsWith("update validation_run set status='DISCARDED'"));
        verify(jdbc).sql(startsWith("update validation_job set status='CANCELLED'"));
        verify(jdbc, never()).sql(startsWith("update validation set"));
        verify(jdbc, never()).sql(startsWith("insert into validation_finding"));
    }

    @Test void newAttemptIsNeverOverwrittenByOldLease() {
        expired("RUNNING", 2, 1, "RUNNING");
        assertThat(writer.expire(3)).isTrue();
        verify(jdbc).sql(startsWith("update validation_run set status='DISCARDED'"));
        verify(jdbc).sql(startsWith("update validation_job set status='CANCELLED'"));
        verify(jdbc, never()).sql(startsWith("update validation set"));
    }

    @Test void finishedRunIsNotRewritten() {
        expired("ERROR", 1, 1, "ERROR");
        assertThat(writer.expire(3)).isTrue();
        verify(jdbc, never()).sql(startsWith("update validation_run set"));
        verify(jdbc).sql(startsWith("update validation_job set status='CANCELLED'"));
    }

    @Test void currentExpiredRunCreatesRetryFinding() {
        expired("RUNNING", 1, 1, "RUNNING");
        assertThat(writer.expire(3)).isTrue();
        verify(jdbc).sql(startsWith("insert into validation_finding"));
        verify(jdbc).sql(startsWith("update validation_run set status='ERROR'"));
        verify(jdbc).sql(startsWith("update validation set status='ERROR'"));
        verify(jdbc).sql(startsWith("update validation_job set status='FAILED'"));
        var order = inOrder(jdbc);
        order.verify(jdbc).sql(contains("select t.project_id,v.task_id,j.lease_token"));
        order.verify(jdbc).sql(startsWith("select id from project"));
        order.verify(jdbc).sql(startsWith("select id from task"));
        order.verify(jdbc).sql(startsWith("select status,current_attempt from validation"));
        order.verify(jdbc).sql(startsWith("select id,attempt,status,kind,lease_token from validation_run"));
        order.verify(jdbc).sql(startsWith("select lease_token from validation_job"));
    }

    @Test void leaseAlreadyRecoveredIsNoOp() {
        when(identityResult.listOfRows()).thenReturn(List.of());
        assertThat(writer.expire(3)).isFalse();
        verify(jdbc, never()).sql(startsWith("update validation_job set"));
    }

    @Test void missingCurrentRunIsCreatedBeforeErrorFinding() {
        when(validationResult.listOfRows()).thenReturn(List.of(Map.of("status", "RUNNING", "current_attempt", 1)));
        when(runResult.listOfRows()).thenReturn(List.of());
        assertThat(writer.expire(3)).isTrue();
        verify(jdbc).sql(startsWith("insert into validation_run"));
        verify(jdbc).sql(startsWith("insert into validation_finding"));
        verify(jdbc).sql(startsWith("update validation_job set status='FAILED'"));
    }

    @Test void unmatchedOldLeaseCannotCreateAnotherRunForCurrentAttempt() {
        when(validationResult.listOfRows()).thenReturn(List.of(Map.of("status", "RUNNING", "current_attempt", 2)));
        when(runResult.listOfRows()).thenReturn(List.of(Map.of("id", 5L, "attempt", 2,
                "status", "RUNNING", "kind", "MODEL", "lease_token", UUID.randomUUID())));
        assertThat(writer.expire(3)).isTrue();
        verify(jdbc, never()).sql(startsWith("insert into validation_run"));
        verify(jdbc, never()).sql(startsWith("update validation set"));
        verify(jdbc).sql(startsWith("update validation_job set status='CANCELLED'"));
    }

    @Test void leaseChangedAfterIdentityReadIsNotTouched() {
        expired("RUNNING", 1, 1, "RUNNING");
        when(jobResult.listOfRows()).thenReturn(List.of());
        assertThat(writer.expire(3)).isFalse();
        verify(jdbc, never()).sql(startsWith("insert into validation_finding"));
        verify(jdbc, never()).sql(startsWith("update validation_job set"));
    }
}
