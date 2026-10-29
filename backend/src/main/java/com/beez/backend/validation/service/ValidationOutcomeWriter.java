package com.beez.backend.validation.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Commits one already leased validation attempt. Search hits are not proof of complete recall. */
@Service
public class ValidationOutcomeWriter {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ValidationRuleChecker rules;
    private final ValidationInputAssembler assembler;
    public ValidationOutcomeWriter(JdbcClient jdbc, ObjectMapper json, ValidationRuleChecker rules,
                                   ValidationInputAssembler assembler) {
        this.jdbc = jdbc; this.json = json; this.rules = rules; this.assembler = assembler;
    }

    public record Execution(long projectId, long taskId, long validationId, long runId, int attempt,
                            UUID leaseToken, long baselineId, long planRevisionId,
                            Map<Long, Long> relatedPlanRevisions, String provider) {
        public Execution { relatedPlanRevisions = Map.copyOf(relatedPlanRevisions); }
    }

    public enum Applied { APPLIED, DISCARDED }

    @Transactional
    public Applied complete(Execution execution, Map<String, Object> request,
                            Map<String, Object> response, boolean searchComplete) {
        lockContext(execution);
        List<Map<String, Object>> usage;
        try { usage = maps(response.get("usage")); validateUsage(execution, usage); }
        catch (IllegalArgumentException bad) { return failLocked(execution, "invalid_ai_output", false, List.of(), "RETRY", null); }
        saveUsage(execution, usage);
        if (!owned(execution)) return discardIfObsolete(execution);
        Map<String, Object> result;
        try {
            if (!"MODEL".equals(response.get("origin")) || !(response.get("model") instanceof String model)
                    || model.isBlank() || usage.size() != 1 || !model.equals(usage.getFirst().get("model"))
                    || !Boolean.FALSE.equals(usage.getFirst().get("failed"))) throw new IllegalArgumentException();
            result = map(response.get("result"));
            verify(execution, request, result);
        } catch (IllegalArgumentException invalid) {
            return failLocked(execution, "invalid_ai_output", false, List.of(), "RETRY", null);
        }
        boolean fresh = fresh(execution, request);
        if (!owned(execution)) return discardIfObsolete(execution);
        if (!fresh) return stale(execution);
        var inputPlan = map(map(request.get("plan_revision")).get("plan"));
        Set<Long> itemIds = new HashSet<>();
        for (var item : maps(request.get("approved_items"))) itemIds.add(longId(item.get("design_item_id")));
        Object packet = inputPlan.get("context_packet_id");
        if (packet != null && !(packet instanceof String)) throw new IllegalArgumentException("invalid packet reference");
        // Prevent a concurrent new/changed question from slipping between this recheck
        // and the result commit. PostgreSQL releases the table lock with the transaction.
        jdbc.sql("lock table planning_question in share mode").update();
        List<String> unresolved = assembler.loadUnresolved(execution.projectId(), execution.taskId(),
                execution.baselineId(), itemIds, (String) packet);
        if (!owned(execution)) return discardIfObsolete(execution);
        if (!unresolved.equals(request.get("unresolved"))) return stale(execution);
        lockEvidenceRows(request);
        var sourceSnapshot = assembler.loadEvidence(execution.projectId(), execution.baselineId(),
                maps(request.get("approved_items")));
        if (!owned(execution)) return discardIfObsolete(execution);
        if (!sourceSnapshot.evidence().equals(request.get("evidence"))) return stale(execution);
        searchComplete = searchComplete && sourceSnapshot.unavailableVersionIds().isEmpty();
        String analysis = (String) result.get("analysis_status");
        if ("PARTIAL".equals(analysis) || !searchComplete) {
            return failLocked(execution, "evidence_insufficient", false, List.of(), "HUMAN_REVIEW",
                    summary(request, result, execution, searchComplete));
        }
        // A different task can acquire a conflicting change or dependency while the model
        // runs. Re-evaluate under the project/task locks before applying any result.
        var ruleResult = rules.check(execution.projectId(), execution.taskId(),
                execution.planRevisionId(), execution.baselineId());
        if (!owned(execution)) return discardIfObsolete(execution);
        if (!ruleResult.findings().isEmpty()) return stale(execution);
        String status = classify(maps(result.get("findings")), existingRuleFindings(execution.validationId()));
        var diagnostics = summary(request, result, execution, searchComplete);
        diagnostics.put("rule_checks", ruleResult.checkedRules());
        diagnostics.put("unsupported_rules", ruleResult.unsupportedRules());
        diagnostics.put("rules_verified", ruleResult.allRulesVerified());
        // Model COMPLETE does not prove that a shortened API specification had enough
        // information to compare request and response fields. Keep the MVP conservative.
        if (!"NEEDS_REVIEW".equals(status) && hasInsufficientApiDetail(request)) {
            diagnostics.put("missing_api_details", true);
            return failLocked(execution, "evidence_insufficient", false, List.of(), "HUMAN_REVIEW", diagnostics);
        }
        // An unconfirmed search candidate is not an authoritative dependency. Until
        // its relevance can be recorded and approved, do not issue PASS or NEEDS_INPUT.
        boolean packetUnverified = map(map(request.get("plan_revision")).get("plan")).get("context_packet_id") != null;
        if (packetUnverified) diagnostics.put("context_packet_unverified", true);
        if (!"NEEDS_REVIEW".equals(status)
                && (!ruleResult.allRulesVerified() || hasUnconfirmedCandidates(request) || packetUnverified)) {
            return failLocked(execution, "rules_unverified", false, List.of(), "HUMAN_REVIEW", diagnostics);
        }
        storeReferences(execution, request);
        for (Map<String, Object> finding : maps(result.get("findings"))) insertFinding(execution, finding);
        if (!owned(execution)) throw new IllegalStateException("validation lease expired during result write");
        jdbc.sql("update validation_run set status='OK', finished_at=now(), updated_at=now() where id=:id")
                .param("id", execution.runId()).update();
        jdbc.sql("update validation set status=:status, summary=coalesce(summary,'{}'::jsonb) || cast(:summary as jsonb), finished_at=now(), updated_at=now(), lock_version=lock_version+1 where id=:id")
                .param("status", status).param("summary", stringify(diagnostics))
                .param("id", execution.validationId()).update();
        finishJob(execution, "DONE");
        return Applied.APPLIED;
    }

    @Transactional
    public Applied fail(Execution execution, String code, boolean timeout, List<Map<String, Object>> usage) {
        lockContext(execution);
        validateUsage(execution, usage);
        saveUsage(execution, usage);
        return failLocked(execution, code, timeout, List.of(), "RETRY", null);
    }

    /** Discard a changed input snapshot only while this worker still owns the live lease. */
    @Transactional
    public Applied staleIfOwned(Execution execution) {
        lockContext(execution);
        if (!owned(execution)) return discardIfObsolete(execution);
        return stale(execution);
    }

    private Applied failLocked(Execution e, String code, boolean timeout, List<Map<String, Object>> ignored,
                               String resolution, Map<String, Object> details) {
        if (!owned(e)) return discardIfObsolete(e);
        String safeCode = Set.of("model_timeout", "model_unavailable", "invalid_ai_output", "evidence_insufficient",
                "search_unavailable", "invalid_ai_input", "worker_failure", "rules_unverified").contains(code) ? code : "worker_failure";
        jdbc.sql("insert into validation_finding(validation_id,validation_run_id,source,kind,severity,message,sources,resolution,next_action) values (:validation,:run,'MODEL','ERROR','BLOCKING',:message,'[]'::jsonb,:resolution,:action)")
                .param("validation", e.validationId()).param("run", e.runId()).param("message", safeCode)
                .param("resolution", resolution).param("action", resolution.equals("RETRY") ? "검증을 다시 시도하세요." : "근거와 검사 범위를 보완해 새 검증을 요청하세요.").update();
        jdbc.sql("update validation_run set status=:status,error=:error,finished_at=now(),updated_at=now() where id=:id")
                .param("status", timeout ? "TIMEOUT" : "ERROR").param("error", safeCode).param("id", e.runId()).update();
        Map<String, Object> diagnostic = details == null ? Map.of("attempt", e.attempt(), "run_id", Long.toString(e.runId()), "error_code", safeCode) : new LinkedHashMap<>(details);
        diagnostic = new LinkedHashMap<>(diagnostic);
        diagnostic.put("error_code", safeCode);
        diagnostic.put("execution_error_code", executionErrorCode(safeCode, timeout, details));
        jdbc.sql("update validation set status='ERROR',summary=coalesce(summary,'{}'::jsonb) || cast(:summary as jsonb),finished_at=now(),updated_at=now(),lock_version=lock_version+1 where id=:id")
                .param("summary", stringify(diagnostic)).param("id", e.validationId()).update();
        // The lease may expire while the error transaction is writing findings.
        // A rollback lets the recovery worker terminate the attempt safely.
        if (!leaseLive(e)) throw new IllegalStateException("validation lease expired during failure write");
        finishJob(e, "FAILED");
        return Applied.APPLIED;
    }

    private void lockContext(Execution e) {
        // Explicit order shared with expire(): project, task, validation, run, job.
        if (jdbc.sql("select id from project where id=:id for update").param("id", e.projectId()).query().listOfRows().size() != 1
                || jdbc.sql("select id from task where id=:id and project_id=:project for update")
                    .param("id", e.taskId()).param("project", e.projectId()).query().listOfRows().size() != 1
                || jdbc.sql("select id from validation where id=:id and task_id=:task for update")
                    .param("id", e.validationId()).param("task", e.taskId()).query().listOfRows().size() != 1
                || jdbc.sql("select id from validation_run where id=:id and validation_id=:validation for update")
                    .param("id", e.runId()).param("validation", e.validationId()).query().listOfRows().size() != 1
                || jdbc.sql("select id from validation_job where validation_id=:validation for update")
                    .param("validation", e.validationId()).query().listOfRows().size() != 1) {
            throw new IllegalArgumentException("validation execution does not belong to project");
        }
    }

    private boolean owned(Execution e) {
        return jdbc.sql("""
                select count(*) from validation v join validation_run r on r.validation_id=v.id
                join validation_job j on j.validation_id=v.id
                where v.id=:validation and v.status='RUNNING' and v.current_attempt=:attempt
                  and r.id=:run and r.attempt=:attempt and r.status='RUNNING' and r.kind='MODEL'
                  and r.lease_token=:token and j.status='LEASED' and j.lease_token=:token and j.lease_until>clock_timestamp()
                """).param("validation", e.validationId()).param("run", e.runId())
                .param("attempt", e.attempt()).param("token", e.leaseToken()).query(Long.class).single() == 1;
    }

    private Applied discardIfObsolete(Execution e) {
        // Do not steal an expired current lease: the recovery worker must create ERROR/RETRY.
        jdbc.sql("""
                update validation_run r set status='DISCARDED',finished_at=now(),updated_at=now()
                 from validation v where r.id=:run and r.validation_id=v.id and r.status='RUNNING'
                   and r.attempt=:attempt and r.lease_token=:token
                   and (v.status<>'RUNNING' or v.current_attempt<>:attempt)
                """).param("run", e.runId()).param("attempt", e.attempt()).param("token", e.leaseToken()).update();
        return Applied.DISCARDED;
    }

    private Applied stale(Execution e) {
        jdbc.sql("update validation_run set status='DISCARDED',finished_at=now(),updated_at=now() where id=:id")
                .param("id", e.runId()).update();
        jdbc.sql("update validation set status='STALE',stale_reason='INPUT_CHANGED',finished_at=now(),updated_at=now(),lock_version=lock_version+1 where id=:id")
                .param("id", e.validationId()).update();
        finishJob(e, "CANCELLED");
        return Applied.DISCARDED;
    }

    private boolean fresh(Execution e, Map<String, Object> request) {
        Long count = jdbc.sql("""
                select count(*) from project p join task t on t.project_id=p.id join validation v on v.task_id=t.id
                 where p.id=:project and t.id=:task and v.id=:validation
                   and p.current_baseline_id=:baseline and v.baseline_id=:baseline
                   and t.current_plan_revision_id=:revision and v.plan_revision_id=:revision
                   and v.input_snapshot_hash=:hash
                """).param("project", e.projectId()).param("task", e.taskId()).param("validation", e.validationId())
                .param("baseline", e.baselineId()).param("revision", e.planRevisionId())
                .param("hash", request.get("input_snapshot_hash")).query(Long.class).single();
        if (count != 1) return false;
        for (var ref : e.relatedPlanRevisions().entrySet()) {
            // The task row is locked until the transaction commits so a result cannot race a plan update.
            var rows = jdbc.sql("select current_plan_revision_id,status from task where id=:task and project_id=:project for update")
                    .param("task", ref.getKey()).param("project", e.projectId()).query().listOfRows();
            if (rows.size() != 1 || !ref.getValue().equals(number(rows.getFirst().get("current_plan_revision_id")))
                    || Set.of("COMPLETED", "CANCELLED").contains(rows.getFirst().get("status"))) return false;
        }
        for (Map<String, Object> item : maps(request.get("approved_items"))) {
            Long id = longId(item.get("design_item_id")), version = longId(item.get("id"));
            if (jdbc.sql("select count(*) from baseline_item where baseline_id=:baseline and design_item_id=:item and design_item_version_id=:version")
                    .param("baseline", e.baselineId()).param("item", id).param("version", version).query(Long.class).single() != 1) return false;
        }
        return true;
    }

    /** Lock the source versions until commit so the verified excerpts cannot change after recheck. */
    private void lockEvidenceRows(Map<String, Object> request) {
        List<Long> versions = maps(request.get("approved_items")).stream()
                .map(item -> longId(item.get("id"))).sorted().toList();
        if (versions.isEmpty()) return;
        var items = jdbc.sql("select id,source_document_version_id from design_item_version where id in (:ids) order by id for share")
                .param("ids", versions).query().listOfRows();
        List<Long> documents = items.stream().map(item -> number(item.get("source_document_version_id")))
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (documents.isEmpty()) return;
        // Document processing updates take the processing row before the version row.
        jdbc.sql("select document_version_id from document_processing where document_version_id in (:ids) order by document_version_id for share")
                .param("ids", documents).query().listOfRows();
        jdbc.sql("select id from document_version where id in (:ids) order by id for share")
                .param("ids", documents).query().listOfRows();
    }

    private boolean leaseLive(Execution e) {
        return jdbc.sql("select count(*) from validation_job where validation_id=:id and status='LEASED' and lease_token=:token and lease_until>clock_timestamp()")
                .param("id", e.validationId()).param("token", e.leaseToken()).query(Long.class).single() == 1;
    }

    private void finishJob(Execution e, String status) {
        jdbc.sql("update validation_job set status=:status,lease_token=null,lease_until=null,updated_at=now() where validation_id=:id")
                .param("status", status).param("id", e.validationId()).update();
    }

    /** Recover the current expired lease or remove an obsolete expired job from the queue. */
    @Transactional
    public boolean expire(long validationId) {
        // This first lookup takes no locks. Every association, lease token and deadline is
        // rechecked after taking the same ordered locks as complete()/fail().
        var identities = jdbc.sql("""
                select t.project_id,v.task_id,j.lease_token from validation v
                  join task t on t.id=v.task_id join validation_job j on j.validation_id=v.id
                 where v.id=:validation and j.status='LEASED' and j.lease_until<=clock_timestamp()
                """).param("validation", validationId).query().listOfRows();
        if (identities.size() != 1) return false;
        var identity = identities.getFirst();
        Object project = identity.get("project_id"), task = identity.get("task_id"), token = identity.get("lease_token");
        if (jdbc.sql("select id from project where id=:id for update").param("id", project).query().listOfRows().size() != 1
                || jdbc.sql("select id from task where id=:id and project_id=:project for update")
                    .param("id", task).param("project", project).query().listOfRows().size() != 1) return false;
        var validations = jdbc.sql("select status,current_attempt from validation where id=:id and task_id=:task for update")
                .param("id", validationId).param("task", task).query().listOfRows();
        if (validations.size() != 1) return false;
        // Lock all runs in a deterministic order before the job. The validation row is
        // already locked, so the current attempt cannot be replaced during recovery.
        var runs = jdbc.sql("select id,attempt,status,kind,lease_token from validation_run where validation_id=:validation order by id for update")
                .param("validation", validationId).query().listOfRows();
        var leases = jdbc.sql("""
                select lease_token from validation_job where validation_id=:id and status='LEASED'
                  and lease_token=:token and lease_until<=clock_timestamp() for update
                """).param("id", validationId).param("token", token).query().listOfRows();
        if (leases.size() != 1) return false;
        var validation = validations.getFirst();
        var job = Map.of("validation_status", validation.get("status"), "current_attempt", validation.get("current_attempt"));
        Map<String, Object> matchingRun = runs.stream().filter(run -> Objects.equals(token, run.get("lease_token"))).findFirst().orElse(null);
        if (matchingRun == null && "RUNNING".equals(job.get("validation_status"))) {
            int currentAttempt = ((Number) job.get("current_attempt")).intValue();
            boolean currentRunExists = runs.stream().anyMatch(run -> run.get("attempt") instanceof Number attempt
                    && attempt.intValue() == currentAttempt);
            if (!currentRunExists) {
                // If claim was interrupted before a run was recorded, create exactly one
                // MODEL run for the same attempt so its ERROR finding has a valid owner.
                Long runId = jdbc.sql("insert into validation_run(validation_id,attempt,lease_token,kind,status,analyzer_version) values (:validation,:attempt,:token,'MODEL','RUNNING','task-validation-recovery') returning id")
                        .param("validation", validationId).param("attempt", currentAttempt).param("token", token)
                        .query(Long.class).single();
                matchingRun = Map.of("id", runId, "attempt", currentAttempt, "status", "RUNNING", "kind", "MODEL");
            }
        }
        if (!isCurrentExpiredRun(job, matchingRun)) {
            // A stale or superseded worker cannot modify the current validation. Release the
            // old job so it does not starve the queue. Never rewrite an already finished run.
            if (matchingRun != null && "RUNNING".equals(matchingRun.get("status"))) {
                jdbc.sql("update validation_run set status='DISCARDED',finished_at=now(),updated_at=now() where id=:id and status='RUNNING'")
                        .param("id", matchingRun.get("id")).update();
            }
            jdbc.sql("update validation_job set status='CANCELLED',lease_token=null,lease_until=null,updated_at=now() where validation_id=:id and status='LEASED' and lease_token=:token")
                    .param("id", validationId).param("token", token).update();
            return true;
        }
        long run = ((Number) matchingRun.get("id")).longValue();
        int attempt = ((Number) job.get("current_attempt")).intValue();
        jdbc.sql("insert into validation_finding(validation_id,validation_run_id,source,kind,severity,message,sources,resolution,next_action) values (:validation,:run,'MODEL','ERROR','BLOCKING','worker_failure','[]'::jsonb,'RETRY','검증을 다시 시도하세요.')")
                .param("validation", validationId).param("run", run).update();
        jdbc.sql("update validation_run set status='ERROR',error='worker_failure',finished_at=now(),updated_at=now() where id=:id")
                .param("id", run).update();
        jdbc.sql("update validation set status='ERROR',summary=coalesce(summary,'{}'::jsonb) || cast(:summary as jsonb),finished_at=now(),updated_at=now(),lock_version=lock_version+1 where id=:id")
                .param("summary", stringify(Map.of("attempt", attempt, "run_id", Long.toString(run), "error_code", "worker_failure", "execution_error_code", "validation_lease_expired")))
                .param("id", validationId).update();
        jdbc.sql("update validation_job set status='FAILED',lease_token=null,lease_until=null,updated_at=now() where validation_id=:id")
                .param("id", validationId).update();
        return true;
    }

    static boolean isCurrentExpiredRun(Map<String, Object> job, Map<String, Object> run) {
        return run != null && "RUNNING".equals(job.get("validation_status"))
                && "RUNNING".equals(run.get("status")) && "MODEL".equals(run.get("kind"))
                && job.get("current_attempt") instanceof Number current
                && run.get("attempt") instanceof Number actual
                && current.intValue() == actual.intValue();
    }

    private static String executionErrorCode(String code, boolean timeout, Map<String, Object> details) {
        if (timeout) return "ai_timeout";
        if (code.equals("model_unavailable")) return "ai_unavailable";
        if (code.equals("invalid_ai_output")) return "ai_invalid_output";
        if (code.equals("search_unavailable")) return "evidence_fetch_failed";
        if (code.equals("evidence_insufficient") && details != null) {
            var analysis = map(details.get("analysis"));
            Set<String> reasons = new HashSet<>();
            for (var item : maps(analysis.get("unassessed"))) reasons.add(text(item.get("reason")));
            if (reasons.equals(Set.of("EVIDENCE_INSUFFICIENT"))) return "ai_evidence_insufficient";
            if (reasons.equals(Set.of("UNSUPPORTED_CONTENT"))) return "ai_unsupported_content";
            if (reasons.size() > 1) return "ai_analysis_incomplete";
        }
        return "validation_execution_failed";
    }

    private void storeReferences(Execution e, Map<String, Object> request) {
        Map<String, String> relations = new HashMap<>();
        for (var ref : maps(map(map(request.get("plan_revision")).get("plan")).get("design_refs"))) {
            relations.put(text(ref.get("design_item_id")), text(ref.get("relation")));
        }
        for (var item : maps(request.get("approved_items"))) {
            long id = longId(item.get("design_item_id")), version = longId(item.get("id"));
            String relation = relations.get(Long.toString(id));
            if (relation == null) continue; // Search results alone are not confirmed design references.
            jdbc.sql("insert into validation_design_ref(validation_id,design_item_id,design_item_version_id,relation) values (:validation,:item,:version,:relation) on conflict do nothing")
                    .param("validation", e.validationId()).param("item", id).param("version", version).param("relation", relation).update();
            if (jdbc.sql("select count(*) from validation_design_ref where validation_id=:validation and design_item_id=:item and design_item_version_id=:version and relation=:relation")
                    .param("validation", e.validationId()).param("item", id).param("version", version).param("relation", relation).query(Long.class).single() != 1) throw new IllegalArgumentException("conflicting design snapshot");
        }
        for (var ref : e.relatedPlanRevisions().entrySet()) {
            jdbc.sql("insert into validation_task_ref(validation_id,related_task_id,related_plan_revision_id) values (:validation,:task,:revision) on conflict do nothing")
                    .param("validation", e.validationId()).param("task", ref.getKey()).param("revision", ref.getValue()).update();
            if (jdbc.sql("select count(*) from validation_task_ref where validation_id=:validation and related_task_id=:task and related_plan_revision_id=:revision")
                    .param("validation", e.validationId()).param("task", ref.getKey()).param("revision", ref.getValue()).query(Long.class).single() != 1) throw new IllegalArgumentException("conflicting task snapshot");
        }
    }

    private List<Map<String, Object>> existingRuleFindings(long validationId) {
        return jdbc.sql("select kind,severity from validation_finding where validation_id=:id and source='RULE'")
                .param("id", validationId).query().listOfRows();
    }

    static String classify(List<Map<String, Object>> findings, List<Map<String, Object>> rules) {
        boolean missingInput = false;
        for (var f : concat(findings, rules)) {
            if (!"BLOCKING".equals(f.get("severity"))) continue;
            if ("MISSING_INPUT".equals(f.get("kind"))) missingInput = true;
            else return "NEEDS_REVIEW";
        }
        return missingInput ? "NEEDS_INPUT" : "PASS";
    }

    private static List<Map<String, Object>> concat(List<Map<String, Object>> a, List<Map<String, Object>> b) {
        var all = new ArrayList<Map<String, Object>>(a); all.addAll(b); return all;
    }

    private void insertFinding(Execution e, Map<String, Object> f) {
        String kind = (String) f.get("kind");
        String resolution = kind.equals("MISSING_INPUT") ? "FIX_PLAN" : "HUMAN_REVIEW";
        jdbc.sql("""
                insert into validation_finding(validation_id,validation_run_id,source,kind,severity,design_item_id,
                    design_item_version_id,related_task_id,message,sources,resolution,next_action)
                values (:validation,:run,'MODEL',:kind,:severity,:item,:version,:task,:message,cast(:sources as jsonb),:resolution,:action)
                """).param("validation", e.validationId()).param("run", e.runId()).param("kind", kind)
                .param("severity", f.get("severity")).param("item", optionalId(f.get("design_item_id")))
                .param("version", optionalId(f.get("design_item_version_id"))).param("task", optionalId(f.get("related_task_id")))
                .param("message", f.get("message")).param("sources", stringify(f.get("sources")))
                .param("resolution", resolution).param("action", f.get("suggested_action")).update();
    }

    private static Map<String, Object> summary(Map<String, Object> request, Map<String, Object> result, Execution e, boolean searchComplete) {
        var value = new LinkedHashMap<String, Object>();
        value.put("attempt", e.attempt()); value.put("run_id", Long.toString(e.runId()));
        value.put("checks", request.get("checks")); value.put("analysis", result);
        // Preserve searched candidates as diagnostic input, not as an authoritative edge.
        value.put("candidate_designs", candidateDesigns(request));
        value.put("search_complete", searchComplete); value.put("retrieval_coverage", "UNKNOWN");
        return value;
    }

    static boolean hasInsufficientApiDetail(Map<String, Object> request) {
        for (var item : maps(request.get("approved_items"))) {
            if (!"API_CONTRACT".equals(item.get("kind"))) continue;
            var content = map(item.get("content"));
            if (!(content.get("request_fields") instanceof List<?>)
                    || !(content.get("responses") instanceof List<?> responses) || responses.isEmpty()) return true;
            for (Object response : responses) {
                if (!(response instanceof Map<?, ?> row) || !(row.get("fields") instanceof List<?>)) return true;
            }
        }
        return false;
    }

    static boolean hasUnconfirmedCandidates(Map<String, Object> request) {
        return !candidateDesigns(request).isEmpty();
    }

    private static List<Map<String, Object>> candidateDesigns(Map<String, Object> request) {
        Set<String> confirmed = new HashSet<>();
        for (var ref : maps(map(map(request.get("plan_revision")).get("plan")).get("design_refs"))) {
            confirmed.add(text(ref.get("design_item_id")));
        }
        return maps(request.get("approved_items")).stream()
                .filter(item -> !confirmed.contains(text(item.get("design_item_id"))))
                .map(item -> Map.<String, Object>of("design_item_id", item.get("design_item_id"),
                        "design_item_version_id", item.get("id")))
                .toList();
    }

    static void verify(Execution e, Map<String, Object> request, Map<String, Object> result) {
        if (!Long.toString(e.projectId()).equals(request.get("project_id"))
                || !Long.toString(e.validationId()).equals(request.get("validation_id"))
                || !(request.get("attempt") instanceof Number n) || n.intValue() != e.attempt()) throw new IllegalArgumentException();
        var targetPlan = map(request.get("plan_revision"));
        if (!Long.toString(e.taskId()).equals(targetPlan.get("task_id"))
                || !Long.toString(e.planRevisionId()).equals(targetPlan.get("id"))) throw new IllegalArgumentException();
        Map<String, Map<String, Object>> checks = new HashMap<>();
        for (var check : maps(request.get("checks"))) {
            String key = text(check.get("key")); if (checks.put(key, check) != null) throw new IllegalArgumentException();
        }
        if (checks.isEmpty()) throw new IllegalArgumentException();
        Set<String> checked = stringSet(result.get("checked_keys")); Set<String> all = new HashSet<>(checked);
        var unassessed = maps(result.get("unassessed"));
        for (var item : unassessed) if (!all.add(text(item.get("check_key")))
                || !Set.of("EVIDENCE_INSUFFICIENT", "UNSUPPORTED_CONTENT").contains(item.get("reason"))
                || text(item.get("message")).isBlank()) throw new IllegalArgumentException();
        if (!all.equals(checks.keySet()) || !("COMPLETE".equals(result.get("analysis_status")) && unassessed.isEmpty()
                || "PARTIAL".equals(result.get("analysis_status")) && !unassessed.isEmpty())) throw new IllegalArgumentException();
        if ("COMPLETE".equals(result.get("analysis_status")) && !((List<?>) request.get("unresolved")).isEmpty()) throw new IllegalArgumentException();
        Map<String, Map<String, Object>> items = index(maps(request.get("approved_items")), "id");
        Map<String, Map<String, Object>> plans = index(maps(request.get("related_plans")), "id");
        Map<Long, Long> actualRevisions = new HashMap<>();
        for (var plan : plans.values()) {
            if (actualRevisions.put(longId(plan.get("task_id")), longId(plan.get("id"))) != null) throw new IllegalArgumentException();
        }
        if (!actualRevisions.equals(e.relatedPlanRevisions())) throw new IllegalArgumentException();
        Map<String, Map<String, Object>> evidence = index(maps(request.get("evidence")), "evidence_id");
        for (var f : maps(result.get("findings"))) {
            String key = text(f.get("check_key")); if (!checked.contains(key)) throw new IllegalArgumentException();
            String kind = text(f.get("kind")), severity = text(f.get("severity"));
            // There is currently no agreed machine-readable proof of policy absence.
            if ("MISSING_POLICY".equals(kind)) throw new IllegalArgumentException();
            if (!Set.of("MISMATCH", "MISSING_INPUT", "MISSING_POLICY", "CONFLICT", "STALE_REF").contains(kind)
                    || !Set.of("INFO", "WARNING", "BLOCKING").contains(severity)
                    || text(f.get("message")).isBlank() || text(f.get("suggested_action")).isBlank()
                    || (Set.of("MISSING_INPUT", "MISSING_POLICY").contains(kind) && !"BLOCKING".equals(severity))) throw new IllegalArgumentException();
            var check = checks.get(key); Set<String> versionIds = stringSet(check.get("design_item_version_ids"));
            Set<String> planIds = stringSet(check.get("related_plan_revision_ids"));
            Object itemId = f.get("design_item_id"), version = f.get("design_item_version_id"), task = f.get("related_task_id");
            if ((itemId == null) != (version == null)) throw new IllegalArgumentException();
            if (version != null && (!versionIds.contains(version) || !items.containsKey(version)
                    || !itemId.equals(items.get(version).get("design_item_id")))) throw new IllegalArgumentException();
            if (task != null && planIds.stream().noneMatch(id -> plans.containsKey(id) && task.equals(plans.get(id).get("task_id")))) throw new IllegalArgumentException();
            if (Set.of("MISMATCH", "CONFLICT", "STALE_REF").contains(kind) && version == null && task == null) throw new IllegalArgumentException();
            Set<Map<String, Object>> allowed = new HashSet<>();
            for (String id : versionIds) if (items.containsKey(id) && items.get(id).get("source") instanceof Map<?, ?> source) allowed.add(map(source));
            for (var ev : evidence.values()) if (versionIds.contains(ev.get("design_item_version_id"))) allowed.add(map(ev.get("source")));
            var sources = maps(f.get("sources")); if (!allowed.containsAll(sources)) throw new IllegalArgumentException();
            for (String id : stringSet(f.get("evidence_ids"))) {
                var ev = evidence.get(id);
                if (ev == null || !versionIds.contains(ev.get("design_item_version_id")) || !sources.contains(map(ev.get("source")))) throw new IllegalArgumentException();
            }
        }
    }

    private static Map<String, Map<String, Object>> index(List<Map<String, Object>> values, String key) {
        var out = new HashMap<String, Map<String, Object>>();
        for (var value : values) if (out.put(text(value.get(key)), value) != null) throw new IllegalArgumentException();
        return out;
    }

    private void validateUsage(Execution e, List<Map<String, Object>> usage) {
        if (e.provider() == null || e.provider().isBlank()) throw new IllegalArgumentException("provider is required");
        Set<String> seen = new HashSet<>();
        for (var u : usage) {
            String key = text(u.get("request_key"));
            if (!key.matches("llm-validate-" + e.validationId() + "-attempt-" + e.attempt() + "-call-[1-9][0-9]*")
                    || !seen.add(key) || !"TASK_VALIDATION".equals(u.get("feature")) || text(u.get("model")).isBlank()
                    || !(u.get("failed") instanceof Boolean) || !(u.get("tokens_known") instanceof Boolean known)
                    || !(u.get("latency_ms") instanceof Number latency) || latency.longValue() < 0
                    || (known && (!(u.get("input_tokens") instanceof Number) || !(u.get("output_tokens") instanceof Number)))
                    || (!known && (u.get("input_tokens") != null || u.get("output_tokens") != null))
                    || ((u.get("estimated_cost_amount") == null) != (u.get("currency") == null))) throw new IllegalArgumentException();
        }
    }

    private void saveUsage(Execution e, List<Map<String, Object>> usage) {
        for (var u : usage) {
            jdbc.sql("""
                    insert into llm_usage(project_id,feature,request_key,task_id,validation_run_id,provider,model,tokens_known,
                        input_tokens,output_tokens,latency_ms,estimated_cost_amount,currency,failed,origin)
                    values (:project,'TASK_VALIDATION',:key,:task,:run,:provider,:model,:known,:input,:output,:latency,
                        cast(:cost as numeric),:currency,:failed,'model') on conflict (request_key) do nothing
                    """).param("project", e.projectId()).param("key", u.get("request_key")).param("task", e.taskId())
                    .param("run", e.runId()).param("provider", e.provider()).param("model", u.get("model"))
                    .param("known", u.get("tokens_known")).param("input", u.get("input_tokens"))
                    .param("output", u.get("output_tokens")).param("latency", u.get("latency_ms"))
                    .param("cost", u.get("estimated_cost_amount")).param("currency", u.get("currency"))
                    .param("failed", u.get("failed")).update();
        }
    }

    private String stringify(Object value) {
        return json.writeValueAsString(value);
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException();
        return (Map<String, Object>) value;
    }
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException();
        return values.stream().map(ValidationOutcomeWriter::map).toList();
    }
    private static Set<String> stringSet(Object value) {
        if (!(value instanceof List<?> values)) throw new IllegalArgumentException();
        Set<String> result = new HashSet<>();
        for (Object item : values) if (!result.add(text(item))) throw new IllegalArgumentException();
        return result;
    }
    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException(); return s;
    }
    private static Long longId(Object value) {
        try { long id = Long.parseLong(text(value)); if (id <= 0) throw new IllegalArgumentException(); return id; }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(invalid); }
    }
    private static Long optionalId(Object value) { return value == null ? null : longId(value); }
    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }
}
