package com.beez.backend.validation.service;

import com.beez.backend.ai.AiClient;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.config.BeezProperties;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

/** Runs claimed attempts outside a database transaction; result writes are fenced by lease. */
@Component
public class ValidationWorker {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcClient jdbc;
    private final AiClient ai;
    private final ValidationInputAssembler assembler;
    private final ValidationOutcomeWriter writer;
    private final ValidationRuleOutcomeWriter ruleWriter;
    private final TransactionTemplate tx;
    private final int budgetMs;
    private final boolean enabled;
    private final String provider;

    public ValidationWorker(JdbcClient jdbc, AiClient ai, ValidationInputAssembler assembler,
                            ValidationOutcomeWriter writer, ValidationRuleOutcomeWriter ruleWriter,
                            PlatformTransactionManager manager,
                            BeezProperties properties,
                            @Value("${beez.validation.worker-enabled:false}") boolean enabled,
                            @Value("${beez.validation.provider:gms}") String provider) {
        if (provider == null || !Set.of("gms", "openai").contains(provider)) {
            throw new IllegalArgumentException("beez.validation.provider must be gms or openai");
        }
        this.jdbc = jdbc; this.ai = ai; this.assembler = assembler; this.writer = writer; this.ruleWriter = ruleWriter;
        this.tx = new TransactionTemplate(manager); this.enabled = enabled; this.provider = provider;
        long timeout = properties.ai().readTimeout().toMillis();
        this.budgetMs = (int) Math.max(1, Math.min(60000, timeout - Math.min(2000, timeout / 2)));
    }

    @Scheduled(fixedDelayString = "${beez.validation.poll-millis:2000}")
    public void poll() {
        if (!enabled) return;
        try { runOnce(); }
        catch (RuntimeException e) {
            LoggerFactory.getLogger(getClass()).error("Validation worker failed: {}", e.getClass().getSimpleName());
        }
    }

    record Claim(long projectId, long taskId, long validationId, long runId, int attempt,
                 UUID lease, long baselineId, long revisionId, CallerContext caller) {
        ValidationOutcomeWriter.Execution execution(Map<Long, Long> related, String provider) {
            return new ValidationOutcomeWriter.Execution(projectId, taskId, validationId, runId, attempt,
                    lease, baselineId, revisionId, related, provider, caller);
        }
    }

    public boolean runOnce() {
        var expired = jdbc.sql("select validation_id from validation_job where status='LEASED' and lease_until<=now() order by lease_until,id limit 1")
                .query(Long.class).optional();
        if (expired.isPresent()) {
            writer.expire(expired.get());
            return true;
        }
        Claim claim = tx.execute(status -> claim());
        if (claim == null) return false;
        var execution = claim.execution(Map.of(), provider);
        try {
            if (ruleWriter.apply(execution, claim.caller()) != ValidationRuleOutcomeWriter.Applied.CONTINUE) return true;
        } catch (RuntimeException e) {
            safeFail(execution, "worker_failure", false, List.of());
            return true;
        }
        final ValidationInputAssembler.Result input;
        try {
            input = assembler.assemble(claim.caller(), claim.projectId(), claim.taskId(), claim.revisionId(),
                    claim.validationId(), claim.attempt(), budgetMs);
        } catch (RuntimeException e) {
            if (e instanceof ApiException api && Set.of("stale_plan", "baseline_changed", "related_task_changed",
                    "stale_design_reference", "design_candidate_changed", "stale_context").contains(api.getCode())) {
                writer.staleIfOwned(execution);
            } else {
                safeFail(execution, "search_unavailable", false, List.of());
            }
            return true;
        }
        try {
            if (input.baselineId() != claim.baselineId()) {
                writer.staleIfOwned(execution);
                return true;
            }
            execution = claim.execution(input.relatedPlanRevisions(), provider);
            if (!recordSnapshot(claim, input)) {
                writer.staleIfOwned(execution);
                return true;
            }
            Map<String, Object> response = ai.validatePlan(input.request());
            writer.complete(execution, input.request(), response, input.searchComplete());
        } catch (RestClientResponseException e) {
            safeFail(execution, e.getStatusCode().value() == 504 ? "model_timeout" : "model_unavailable",
                    e.getStatusCode().value() == 504, usage(e.getResponseBodyAsString()));
        } catch (ResourceAccessException e) {
            boolean timedOut = isTimeout(e);
            safeFail(execution, timedOut ? "model_timeout" : "model_unavailable", timedOut, List.of());
        } catch (RuntimeException e) {
            safeFail(execution, "worker_failure", false, List.of());
        }
        return true;
    }

    private Claim claim() {
        var jobs = jdbc.sql("""
                select j.id as job_id, v.id as validation_id,v.task_id,v.plan_revision_id,v.baseline_id,
                       v.current_attempt,t.project_id,v.summary->'request_actor'->>'account_id' as actor_id,
                       v.summary->'request_actor'->>'kind' as actor_kind,
                       v.summary->'request_actor'->>'agent_token_id' as actor_token
                from validation_job j join validation v on v.id=j.validation_id
                join task t on t.id=v.task_id
                where j.status='QUEUED' and j.next_run_at<=now() and v.status='RUNNING'
                order by j.next_run_at,j.id limit 1 for update of j,v skip locked
                """).query().listOfRows();
        if (jobs.isEmpty()) return null;
        var row = jobs.getFirst();
        long id = number(row, "validation_id"), job = number(row, "job_id");
        int attempt = ((Number) row.get("current_attempt")).intValue();
        UUID lease = UUID.randomUUID();
        jdbc.sql("update validation_job set status='LEASED',attempts=:attempt,lease_token=:token,lease_until=now()+interval '3 minutes',updated_at=now() where id=:id")
                .param("attempt", attempt).param("token", lease).param("id", job).update();
        long run = jdbc.sql("insert into validation_run(validation_id,attempt,lease_token,kind,status,analyzer_version) values(:id,:attempt,:token,'MODEL','RUNNING','task-validation-v1') returning id")
                .param("id", id).param("attempt", attempt).param("token", lease).query(Long.class).single();
        CallerContext caller = parseCaller(row);
        return new Claim(number(row, "project_id"), number(row, "task_id"), id, run, attempt, lease,
                number(row, "baseline_id"), number(row, "plan_revision_id"), caller);
    }

    private static CallerContext parseCaller(Map<String, Object> row) {
        try {
            long account = Long.parseLong((String) row.get("actor_id"));
            if ("HUMAN".equals(row.get("actor_kind"))) return CallerContext.human(account);
            if ("AGENT".equals(row.get("actor_kind"))) return CallerContext.agent(account, Long.parseLong((String) row.get("actor_token")));
        } catch (RuntimeException ignored) { /* malformed context becomes a fenced execution failure */ }
        return null;
    }

    private boolean recordSnapshot(Claim claim, ValidationInputAssembler.Result input) {
        return Boolean.TRUE.equals(tx.execute(status -> jdbc.sql("""
                update validation v set input_snapshot_hash=:hash,updated_at=now()
                 where v.id=:id and v.status='RUNNING' and v.current_attempt=:attempt
                   and v.baseline_id=:baseline and v.plan_revision_id=:revision
                   and (v.input_snapshot_hash like 'pending:%' or v.input_snapshot_hash=:hash)
                   and exists (select 1 from validation_job j where j.validation_id=v.id and j.status='LEASED'
                       and j.lease_token=:lease and j.lease_until>now())
                """).param("hash", input.request().get("input_snapshot_hash")).param("id", claim.validationId())
                .param("attempt", claim.attempt()).param("baseline", claim.baselineId()).param("revision", claim.revisionId())
                .param("lease", claim.lease()).update() == 1));
    }

    private void safeFail(ValidationOutcomeWriter.Execution execution, String code, boolean timeout,
                          List<Map<String, Object>> usage) {
        try { writer.fail(execution, code, timeout, usage); }
        catch (IllegalArgumentException invalidUsage) { writer.fail(execution, code, timeout, List.of()); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> usage(String body) {
        try {
            Object data = JSON.readValue(body, Map.class).get("usage");
            if (!(data instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof Map<?, ?>))) return List.of();
            return (List<Map<String, Object>>) data;
        } catch (RuntimeException ignored) { return List.of(); }
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) return true;
        }
        return false;
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
}
