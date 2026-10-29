package com.beez.backend.validation.service;

import static com.beez.backend.designitem.support.DesignJson.hash;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.auth.CallerContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Atomic terminal outcomes for explicit, blocking rule findings; no AI call is made here. */
@Service
public class ValidationRuleOutcomeWriter {
    public enum Applied { CONTINUE, FINALIZED, DISCARDED }
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final ProjectAccessChecker access;
    private final ValidationRuleChecker checker;

    public ValidationRuleOutcomeWriter(JdbcClient jdbc, ObjectMapper json,
                                       ProjectAccessChecker access, ValidationRuleChecker checker) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.checker = checker;
    }

    /** Run under a project/task snapshot lock; the claim must still own the unexpired lease. */
    @Transactional
    public Applied apply(ValidationOutcomeWriter.Execution e, CallerContext caller) {
        if (caller == null) throw new IllegalArgumentException("validation caller is unavailable");
        access.requireMember(caller, e.projectId());
        var projects = jdbc.sql("select current_baseline_id from project where id=:project for update")
                .param("project", e.projectId()).query().listOfRows();
        if (projects.size() != 1) return Applied.DISCARDED;
        // TaskStateService and plan revision commands serialize through the project lock.
        var tasks = jdbc.sql("select id,assignee_member_id,current_plan_revision_id from task where project_id=:project order by id for update")
                .param("project", e.projectId()).query().listOfRows();
        var task = tasks.stream().filter(row -> number(row.get("id")) == e.taskId()).findFirst().orElse(null);
        if (task == null) return Applied.DISCARDED;
        var actor = access.requireMember(caller, e.projectId());
        actor.requireMemberId(nullableNumber(task.get("assignee_member_id")));
        var records = jdbc.sql("""
                select v.status,v.current_attempt,v.baseline_id,v.plan_revision_id,v.input_snapshot_hash,r.status as run_status,
                       r.attempt as run_attempt,r.lease_token as run_lease,j.status as job_status,
                       j.lease_token as job_lease,(j.lease_until>clock_timestamp()) as lease_valid
                from validation v join validation_run r on r.validation_id=v.id
                join validation_job j on j.validation_id=v.id
                where v.id=:validation and v.task_id=:task and r.id=:run
                for update of v,r,j
                """).param("validation", e.validationId()).param("task", e.taskId()).param("run", e.runId()).query().listOfRows();
        if (records.size() != 1) return Applied.DISCARDED;
        var row = records.getFirst();
        if (!"RUNNING".equals(row.get("status")) || !"RUNNING".equals(row.get("run_status"))
                || !"LEASED".equals(row.get("job_status")) || !Boolean.TRUE.equals(row.get("lease_valid"))
                || number(row.get("current_attempt")) != e.attempt() || number(row.get("run_attempt")) != e.attempt()
                || !e.leaseToken().equals(row.get("run_lease")) || !e.leaseToken().equals(row.get("job_lease"))) return Applied.DISCARDED;
        if (!Long.valueOf(e.baselineId()).equals(nullableNumber(projects.getFirst().get("current_baseline_id")))
                || number(row.get("baseline_id")) != e.baselineId() || number(row.get("plan_revision_id")) != e.planRevisionId()
                || !Long.valueOf(e.planRevisionId()).equals(nullableNumber(task.get("current_plan_revision_id")))) {
            jdbc.sql("update validation_run set status='DISCARDED',finished_at=now(),updated_at=now() where id=:id")
                    .param("id", e.runId()).update();
            jdbc.sql("update validation set status='STALE',stale_reason='INPUT_CHANGED',finished_at=now(),updated_at=now(),lock_version=lock_version+1 where id=:id")
                    .param("id", e.validationId()).update();
            finish(e.validationId(), "CANCELLED");
            return Applied.DISCARDED;
        }
        var result = checker.check(e.projectId(), e.taskId(), e.planRevisionId(), e.baselineId());
        if (!leaseValid(e.validationId())) return Applied.DISCARDED;
        var summary = new LinkedHashMap<String, Object>();
        summary.put("rule_checks", result.checkedRules());
        summary.put("unsupported_rules", result.unsupportedRules());
        summary.put("rules_verified", result.allRulesVerified());
        jdbc.sql("update validation set summary=coalesce(summary,'{}'::jsonb) || cast(:summary as jsonb),updated_at=now() where id=:id")
                .param("summary", json.writeValueAsString(summary)).param("id", e.validationId()).update();
        if (result.findings().isEmpty()) return Applied.CONTINUE;
        // A previously captured full input must not be replaced by different retry facts.
        if (!(row.get("input_snapshot_hash") instanceof String previous) || !previous.startsWith("pending:")) {
            jdbc.sql("update validation_run set status='DISCARDED',finished_at=now(),updated_at=now() where id=:id")
                    .param("id", e.runId()).update();
            jdbc.sql("update validation set status='STALE',stale_reason='INPUT_CHANGED',finished_at=now(),updated_at=now(),lock_version=lock_version+1 where id=:id")
                    .param("id", e.validationId()).update();
            finish(e.validationId(), "CANCELLED");
            return Applied.DISCARDED;
        }
        for (var finding : result.findings()) {
            jdbc.sql("""
                    insert into validation_finding(validation_id,validation_run_id,source,kind,severity,design_item_id,
                        design_item_version_id,related_task_id,message,sources,resolution,next_action)
                    values(:validation,:run,'RULE',:kind,:severity,:item,:version,:related,:message,'[]'::jsonb,:resolution,:action)
                    """).param("validation", e.validationId()).param("run", e.runId()).param("kind", finding.kind())
                    .param("severity", finding.severity()).param("item", finding.designItemId()).param("version", finding.designItemVersionId())
                    .param("related", finding.relatedTaskId()).param("message", finding.message())
                    .param("resolution", finding.resolution()).param("action", finding.nextAction()).update();
            if (finding.relatedTaskId() != null && finding.relatedTaskId() != e.taskId()) {
                jdbc.sql("""
                        insert into validation_task_ref(validation_id,related_task_id,related_plan_revision_id)
                        select :validation,id,current_plan_revision_id from task
                         where id=:task and project_id=:project and current_plan_revision_id is not null
                        on conflict do nothing
                        """).param("validation", e.validationId()).param("task", finding.relatedTaskId()).param("project", e.projectId()).update();
            }
        }
        jdbc.sql("""
                insert into validation_design_ref(validation_id,design_item_id,design_item_version_id,relation)
                select :validation,design_item_id,design_item_version_id,relation from task_plan_design_ref
                 where plan_revision_id=:revision on conflict do nothing
                """).param("validation", e.validationId()).param("revision", e.planRevisionId()).update();
        // A lease can expire while SQL is running even when its row is locked. Roll back all findings.
        if (!leaseValid(e.validationId())) throw new IllegalStateException("validation lease expired during rule execution");
        var ruleInput = new LinkedHashMap<String, Object>();
        ruleInput.put("project_id", Long.toString(e.projectId()));
        ruleInput.put("task_id", Long.toString(e.taskId()));
        ruleInput.put("plan_revision_id", Long.toString(e.planRevisionId()));
        ruleInput.put("baseline_id", Long.toString(e.baselineId()));
        ruleInput.put("rule_version", "validation-rule-v1");
        ruleInput.put("checked_rules", result.checkedRules());
        ruleInput.put("unsupported_rules", result.unsupportedRules());
        ruleInput.put("findings", result.findings());
        ruleInput.put("design_refs", jdbc.sql("select design_item_id,design_item_version_id,relation from validation_design_ref where validation_id=:id order by design_item_id")
                .param("id", e.validationId()).query().listOfRows());
        ruleInput.put("related_task_refs", jdbc.sql("select related_task_id,related_plan_revision_id from validation_task_ref where validation_id=:id order by related_task_id")
                .param("id", e.validationId()).query().listOfRows());
        summary.put("rule_input", ruleInput);
        String status = result.findings().stream().anyMatch(f -> !"MISSING_INPUT".equals(f.kind())) ? "NEEDS_REVIEW" : "NEEDS_INPUT";
        jdbc.sql("update validation_run set kind='RULE',status='OK',finished_at=now(),updated_at=now() where id=:id")
                .param("id", e.runId()).update();
        jdbc.sql("update validation set status=:status,input_snapshot_hash=:hash,rule_version='validation-rule-v1',summary=coalesce(summary,'{}'::jsonb) || cast(:summary as jsonb),finished_at=now(),updated_at=now(),lock_version=lock_version+1 where id=:id")
                .param("status", status).param("hash", hash(ruleInput)).param("summary", json.writeValueAsString(summary))
                .param("id", e.validationId()).update();
        finish(e.validationId(), "DONE");
        return Applied.FINALIZED;
    }

    private void finish(long id, String status) {
        jdbc.sql("update validation_job set status=:status,lease_token=null,lease_until=null,updated_at=now() where validation_id=:id")
                .param("id", id).param("status", status).update();
    }
    private boolean leaseValid(long id) {
        return Boolean.TRUE.equals(jdbc.sql("select lease_until>clock_timestamp() from validation_job where validation_id=:id")
                .param("id", id).query(Boolean.class).single());
    }
    private static long number(Object value) { return ((Number) value).longValue(); }
    private static Long nullableNumber(Object value) { return value == null ? null : number(value); }
}
