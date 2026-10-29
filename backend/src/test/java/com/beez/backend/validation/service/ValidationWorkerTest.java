package com.beez.backend.validation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.config.BeezProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.client.HttpServerErrorException;

class ValidationWorkerTest {
    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final AiClient ai = mock(AiClient.class);
    private final ValidationInputAssembler assembler = mock(ValidationInputAssembler.class);
    private final ValidationOutcomeWriter writer = mock(ValidationOutcomeWriter.class);
    private final ValidationRuleOutcomeWriter rules = mock(ValidationRuleOutcomeWriter.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final JdbcClient.StatementSpec expired = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
    private final JdbcClient.StatementSpec queued = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
    private final JdbcClient.StatementSpec lease = paramsStatement();
    private final JdbcClient.StatementSpec run = paramsStatement();
    private final JdbcClient.StatementSpec snapshot = paramsStatement();
    @SuppressWarnings("unchecked")
    private final JdbcClient.MappedQuerySpec<Long> runQuery = mock(JdbcClient.MappedQuerySpec.class);
    private ValidationWorker worker;

    private JdbcClient.StatementSpec paramsStatement() {
        return mock(JdbcClient.StatementSpec.class, invocation ->
                invocation.getMethod().getName().equals("param") ? invocation.getMock() : RETURNS_DEFAULTS.answer(invocation));
    }

    @BeforeEach void setup() {
        var properties = new BeezProperties(null, new BeezProperties.Ai("http://ai", "token", Duration.ofSeconds(1), Duration.ofSeconds(30)), null);
        worker = new ValidationWorker(jdbc, ai, assembler, writer, rules, transactions, properties, true, "gms");
        when(rules.apply(any(), any())).thenReturn(ValidationRuleOutcomeWriter.Applied.CONTINUE);
        when(transactions.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        when(jdbc.sql(contains("select validation_id from validation_job"))).thenReturn(expired);
        when(expired.query(Long.class).optional()).thenReturn(Optional.empty());
        when(jdbc.sql(contains("select j.id as job_id"))).thenReturn(queued);
        when(queued.query().listOfRows()).thenReturn(List.of());
        when(jdbc.sql(startsWith("update validation_job set status='LEASED'"))).thenReturn(lease);
        when(jdbc.sql(startsWith("insert into validation_run"))).thenReturn(run);
        when(run.query(Long.class)).thenReturn(runQuery);
        when(runQuery.single()).thenReturn(4L);
        when(jdbc.sql(contains("update validation v set input_snapshot_hash"))).thenReturn(snapshot);
        when(snapshot.update()).thenReturn(1);
    }

    private void claimed() {
        var row = Map.<String, Object>ofEntries(
                Map.entry("project_id", 1L), Map.entry("task_id", 2L), Map.entry("validation_id", 3L),
                Map.entry("job_id", 20L), Map.entry("current_attempt", 1), Map.entry("baseline_id", 5L),
                Map.entry("plan_revision_id", 6L), Map.entry("actor_id", "7"), Map.entry("actor_kind", "HUMAN"));
        when(queued.query().listOfRows()).thenReturn(List.of(row));
    }

    private ValidationInputAssembler.Result input() {
        return new ValidationInputAssembler.Result(Map.of("input_snapshot_hash", "sha256:abc"), 5L, Map.of(8L, 9L), true, "UNKNOWN");
    }

    @Test void noPendingJobDoesNotCallAi() {
        assertThat(worker.runOnce()).isFalse();
        verifyNoInteractions(ai, assembler, writer);
    }

    @Test void expiredLeaseIsRecoveredBeforeNewWork() {
        when(expired.query(Long.class).optional()).thenReturn(Optional.of(3L));
        assertThat(worker.runOnce()).isTrue();
        verify(writer).expire(3L);
        verifyNoInteractions(ai, assembler);
    }

    @Test void sendsAssembledRequestAndPassesResponseToWriter() {
        claimed();
        var input = input(); var response = Map.<String, Object>of("origin", "MODEL");
        when(assembler.assemble(any(CallerContext.class), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt())).thenReturn(input);
        when(ai.validatePlan(input.request())).thenReturn(response);
        assertThat(worker.runOnce()).isTrue();
        verify(writer).complete(argThat(e -> e.validationId() == 3L && e.runId() == 4L
                && e.relatedPlanRevisions().equals(Map.of(8L, 9L)) && "gms".equals(e.provider())), eq(input.request()), eq(response), eq(true));
    }

    @Test void configuredOpenAiProviderIsPassedToUsageWriter() {
        var properties = new BeezProperties(null, new BeezProperties.Ai("http://ai", "token", Duration.ofSeconds(1), Duration.ofSeconds(30)), null);
        worker = new ValidationWorker(jdbc, ai, assembler, writer, rules, transactions, properties, true, "openai");
        claimed(); var input = input(); var response = Map.<String, Object>of("origin", "MODEL");
        when(assembler.assemble(any(CallerContext.class), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt())).thenReturn(input);
        when(ai.validatePlan(input.request())).thenReturn(response);
        assertThat(worker.runOnce()).isTrue();
        verify(writer).complete(argThat(e -> "openai".equals(e.provider())), eq(input.request()), eq(response), eq(true));
    }

    @Test void rejectsUnknownUsageProviderAtStartup() {
        var properties = new BeezProperties(null, new BeezProperties.Ai("http://ai", "token", Duration.ofSeconds(1), Duration.ofSeconds(30)), null);
        assertThatThrownBy(() -> new ValidationWorker(jdbc, ai, assembler, writer, rules, transactions, properties, true, "other"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsChangedInputHashBeforeCallingAi() {
        claimed(); var input = input(); when(snapshot.update()).thenReturn(0);
        when(assembler.assemble(any(), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt())).thenReturn(input);
        assertThat(worker.runOnce()).isTrue();
        verify(writer).staleIfOwned(argThat(e -> e.validationId() == 3L && e.relatedPlanRevisions().equals(Map.of(8L, 9L))));
        verifyNoInteractions(ai);
    }

    @Test void aiFailureRetainsUsageInErrorPath() {
        claimed(); var input = input();
        when(assembler.assemble(any(), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt())).thenReturn(input);
        String payload = "{\"error\":{\"code\":\"model_unavailable\"},\"usage\":[{\"request_key\":\"llm-validate-3-attempt-1-call-1\"}]}";
        when(ai.validatePlan(input.request())).thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "bad", payload.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertThat(worker.runOnce()).isTrue();
        verify(writer).fail(any(), eq("model_unavailable"), eq(false), argThat(u -> u.size() == 1
                && "llm-validate-3-attempt-1-call-1".equals(u.getFirst().get("request_key"))));
    }

    @Test void searchFailureIsNotMisclassifiedAsAiFailure() {
        claimed();
        when(assembler.assemble(any(), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt()))
                .thenThrow(new HttpServerErrorException(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(worker.runOnce()).isTrue();
        verify(writer).fail(any(), eq("search_unavailable"), eq(false), eq(List.of()));
        verifyNoInteractions(ai);
    }

    @Test void staleDesignDetectedDuringAssemblyIsDiscarded() {
        claimed();
        when(assembler.assemble(any(), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "stale_design_reference", "설계가 변경됨"));
        assertThat(worker.runOnce()).isTrue();
        verify(writer).staleIfOwned(argThat(e -> e.validationId() == 3L));
        verify(writer, never()).fail(any(), anyString(), anyBoolean(), anyList());
        verifyNoInteractions(ai);
    }

    @Test void baselineChangedDuringAssemblyIsDiscarded() {
        claimed();
        var input = new ValidationInputAssembler.Result(Map.of("input_snapshot_hash", "sha256:abc"), 99L, Map.of(), true, "UNKNOWN");
        when(assembler.assemble(any(), eq(1L), eq(2L), eq(6L), eq(3L), eq(1), anyInt())).thenReturn(input);
        assertThat(worker.runOnce()).isTrue();
        verify(writer).staleIfOwned(argThat(e -> e.validationId() == 3L));
        verify(writer, never()).fail(any(), anyString(), anyBoolean(), anyList());
        verifyNoInteractions(ai);
    }

    @Test void blockingRuleFinalizesBeforeSearchAndAi() {
        claimed();
        when(rules.apply(any(), any())).thenReturn(ValidationRuleOutcomeWriter.Applied.FINALIZED);
        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(ai, assembler, writer);
    }

    @Test void staleRuleAttemptDoesNotContinueToAi() {
        claimed();
        when(rules.apply(any(), any())).thenReturn(ValidationRuleOutcomeWriter.Applied.DISCARDED);
        assertThat(worker.runOnce()).isTrue();
        verifyNoInteractions(ai, assembler, writer);
    }

    @Test void ruleExecutionFailureProducesWorkerFailure() {
        claimed();
        when(rules.apply(any(), any())).thenThrow(new IllegalStateException());
        assertThat(worker.runOnce()).isTrue();
        verify(writer).fail(any(), eq("worker_failure"), eq(false), eq(List.of()));
        verifyNoInteractions(ai, assembler);
    }
}
