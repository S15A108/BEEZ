package com.beez.backend.validation.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.hash;
import static com.beez.backend.designitem.support.DesignJson.time;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

/** Project-serialized idempotent acceptance; the worker owns the input snapshot and execution. */
@Service
public class ValidationRequestService {
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;

    public ValidationRequestService(JdbcClient jdbc, ProjectAccessChecker access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @Transactional
    public Map<String, Object> start(CallerContext caller, long project, long task,
                                     String planRevision, int expectedLockVersion, String key) {
        var actor = access.requireMember(caller, project);
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Idempotency-Key는 제어 문자 없는 1~128자입니다.");
        }
        Long revision = Ids.parse(planRevision, "plan_revision_id");
        if (revision == null || revision <= 0 || expectedLockVersion < 0) throw invalid("작업 리비전이 필요합니다.");
        // Recheck membership and ownership while serializing requests for the project.
        var projects = rows("select id,current_baseline_id from project where id=:project for update", Map.of("project", project));
        if (projects.isEmpty()) throw AccessErrors.notFound("project_missing");
        actor = access.requireMember(caller, project);
        var tasks = rows("select id,assignee_member_id,current_plan_revision_id,lock_version from task where id=:task and project_id=:project for update",
                Map.of("project", project, "task", task));
        if (tasks.isEmpty()) throw AccessErrors.notFound("task_not_in_project");
        var taskRow = tasks.getFirst();
        actor.requireMemberId(number(taskRow.get("assignee_member_id")));

        String operation = "validation:start:" + task;
        String fingerprint = hash(Map.of("plan_revision_id", revision.toString(), "expected_lock_version", expectedLockVersion));
        var previous = jdbc.sql("""
                select v.summary->'idempotency' as saved from validation v
                join task t on t.id=v.task_id and t.project_id=:project
                where v.created_at > now() - interval '24 hours'
                  and v.summary->'idempotency'->>'account_id'=:account
                  and v.summary->'idempotency'->>'operation'=:operation
                  and v.summary->'idempotency'->>'key'=:key
                order by v.id desc limit 1
                """).param("project", project).param("account", Long.toString(actor.accountId()))
                .param("operation", operation).param("key", key).query().listOfRows();
        if (!previous.isEmpty()) {
            Map<String, Object> saved = jsonObject(previous.getFirst().get("saved"));
            if (!fingerprint.equals(saved.get("fingerprint"))) {
                throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 요청 키에 다른 내용이 전달되었습니다.");
            }
            return jsonObjectValue(saved.get("response"));
        }
        if (number(taskRow.get("current_plan_revision_id")) == null
                || !revision.equals(number(taskRow.get("current_plan_revision_id")))
                || ((Number) taskRow.get("lock_version")).intValue() != expectedLockVersion) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict", "작업 계획이 변경되었습니다.");
        }
        Long baseline = number(projects.getFirst().get("current_baseline_id"));
        if (baseline == null) throw new ApiException(HttpStatus.CONFLICT, "baseline_missing", "승인된 기준 설계가 없습니다.");

        var requestActor = new LinkedHashMap<String, Object>();
        requestActor.put("account_id", caller.accountId());
        requestActor.put("agent_token_id", caller.agentTokenId());
        requestActor.put("kind", caller.kind().name());
        var summary = Map.of("request_actor", requestActor);
        var created = jdbc.sql("""
                insert into validation(task_id,plan_revision_id,baseline_id,status,trigger,input_snapshot_hash,
                  rule_version,current_attempt,summary)
                values(:task,:revision,:baseline,'RUNNING','AGENT',:snapshot,'validation-v1',1,cast(:summary as jsonb))
                returning id,created_at
                """).param("task", task).param("revision", revision).param("baseline", baseline)
                .param("snapshot", "pending:" + UUID.randomUUID()).param("summary", JSON.writeValueAsString(summary))
                .query().singleRow();
        long id = ((Number) created.get("id")).longValue();
        jdbc.sql("insert into validation_job(validation_id,status,attempts) values(:id,'QUEUED',0)")
                .param("id", id).update();
        var response = new LinkedHashMap<String, Object>();
        response.put("id", Long.toString(id)); response.put("task_id", Long.toString(task));
        response.put("plan_revision_id", revision.toString()); response.put("baseline_id", baseline.toString());
        response.put("status", "RUNNING"); response.put("trigger", "AGENT"); response.put("current_attempt", 1);
        response.put("design_refs", List.of()); response.put("related_task_refs", List.of());
        response.put("findings", List.of()); response.put("cache_hit", false); response.put("created_at", time(created.get("created_at")));
        var idempotency = Map.of("account_id", Long.toString(actor.accountId()), "operation", operation,
                "key", key, "fingerprint", fingerprint, "response", response);
        jdbc.sql("update validation set summary=summary || cast(:addition as jsonb) where id=:id")
                .param("addition", JSON.writeValueAsString(Map.of("idempotency", idempotency))).param("id", id).update();
        return response;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(CallerContext caller, long project, long validation) {
        access.requireMember(caller, project);
        var data = rows("""
                select v.*,r.cache_hit,r.error as run_error from validation v
                join task t on t.id=v.task_id and t.project_id=:project
                left join validation_run r on r.validation_id=v.id and r.attempt=v.current_attempt
                where v.id=:id
                """, Map.of("project", project, "id", validation));
        if (data.isEmpty()) throw AccessErrors.notFound("validation_not_in_project");
        var row = data.getFirst();
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "task_id", "plan_revision_id", "baseline_id")) result.put(key, row.get(key).toString());
        for (String key : List.of("status", "trigger", "current_attempt")) result.put(key, row.get(key));
        result.put("created_at", time(row.get("created_at")));
        if (row.get("finished_at") != null) result.put("finished_at", time(row.get("finished_at")));
        if (row.get("stale_reason") != null) result.put("stale_reason", row.get("stale_reason"));
        if ("ERROR".equals(row.get("status"))) {
            Object code = row.get("summary") == null ? null : jsonObject(row.get("summary")).get("execution_error_code");
            result.put("error_code", code instanceof String ? code : "validation_execution_failed");
        }
        // Partial model candidates are diagnostics, not applied findings or a final verdict.
        // Do not return the summary itself: it also contains actor and idempotency metadata.
        if ("ERROR".equals(row.get("status")) && row.get("summary") != null) {
            var summary = jsonObject(row.get("summary"));
            Object attempt = summary.get("attempt");
            if (attempt instanceof Number n && n.intValue() == ((Number) row.get("current_attempt")).intValue()
                    && summary.get("analysis") instanceof Map<?, ?> analysis) {
                var diagnostics = new LinkedHashMap<String, Object>();
                diagnostics.put("analysis", analysis);
                diagnostics.put("retrieval_coverage", summary.getOrDefault("retrieval_coverage", "UNKNOWN"));
                diagnostics.put("search_complete", Boolean.TRUE.equals(summary.get("search_complete")));
                for (String field : List.of("error_code", "unsupported_rules", "candidate_designs", "missing_api_details", "context_packet_unverified")) {
                    if (summary.containsKey(field)) diagnostics.put(field, summary.get(field));
                }
                result.put("diagnostics", diagnostics);
            }
        }
        // PASS describes only the plans/designs actually assessed, not exhaustive search.
        if ("PASS".equals(row.get("status")) && row.get("summary") != null) {
            var summary = jsonObject(row.get("summary"));
            Object attempt = summary.get("attempt");
            if (attempt instanceof Number n && n.intValue() == ((Number) row.get("current_attempt")).intValue()
                    && summary.get("analysis") instanceof Map<?, ?> analysis) {
                var scope = new LinkedHashMap<String, Object>();
                scope.put("checked_keys", analysis.get("checked_keys"));
                scope.put("retrieval_coverage", summary.getOrDefault("retrieval_coverage", "UNKNOWN"));
                scope.put("description", "전달된 승인 설계와 관련 작업에 대한 검사 결과입니다. 검색 범위 전체의 완전성을 보장하지 않습니다.");
                result.put("analysis_scope", scope);
            }
        }
        result.put("cache_hit", Boolean.TRUE.equals(row.get("cache_hit")));
        result.put("design_refs", rows("select design_item_id,design_item_version_id,relation from validation_design_ref where validation_id=:id order by design_item_id", Map.of("id", validation)).stream()
                .map(ref -> Map.of("design_item_id", ref.get("design_item_id").toString(), "design_item_version_id", ref.get("design_item_version_id").toString(), "relation", ref.get("relation"))).toList());
        result.put("related_task_refs", rows("select related_task_id,related_plan_revision_id from validation_task_ref where validation_id=:id order by related_task_id", Map.of("id", validation)).stream()
                .map(ref -> Map.of("task_id", ref.get("related_task_id").toString(), "plan_revision_id", ref.get("related_plan_revision_id").toString())).toList());
        var findingRows = rows("""
                select f.* from validation_finding f join validation v on v.id=f.validation_id
                left join validation_run r on r.id=f.validation_run_id
                where f.validation_id=:id and (f.kind <> 'ERROR' or r.attempt=v.current_attempt)
                order by f.id
                """, Map.of("id", validation));
        var findings = new ArrayList<Map<String, Object>>();
        for (var f : findingRows) {
            var finding = new LinkedHashMap<String, Object>();
            for (String key : List.of("id", "design_item_id", "design_item_version_id", "related_task_id", "validation_run_id")) {
                if (f.get(key) != null) finding.put(key, f.get(key).toString());
            }
            for (String key : List.of("source", "kind", "severity", "message", "resolution", "next_action")) {
                if (f.get(key) != null) finding.put(key, f.get(key));
            }
            finding.put("sources", JSON.readValue(f.get("sources").toString(), new TypeReference<List<Object>>() {}));
            findings.add(finding);
        }
        result.put("findings", findings);
        access.requireMember(caller, project);
        return result;
    }

    @Transactional
    public Map<String, Object> retry(CallerContext caller, long project, long validation, int expectedAttempt, String key) {
        var actor = access.requireMember(caller, project);
        if (key == null || key.isBlank() || key.length() > 128 || key.chars().anyMatch(Character::isISOControl) || expectedAttempt < 1) {
            throw invalid("Idempotency-Key와 expected_attempt가 필요합니다.");
        }
        var projects = rows("select id,current_baseline_id from project where id=:project for update", Map.of("project", project));
        if (projects.isEmpty()) throw AccessErrors.notFound("project_missing");
        actor = access.requireMember(caller, project);
        var records = rows("""
                select v.id,v.task_id,v.plan_revision_id,v.baseline_id,v.status,v.current_attempt,v.summary,
                       t.assignee_member_id,t.current_plan_revision_id
                from validation v join task t on t.id=v.task_id and t.project_id=:project
                where v.id=:id for update of v,t
                """, Map.of("project", project, "id", validation));
        if (records.isEmpty()) throw AccessErrors.notFound("validation_not_in_project");
        var record = records.getFirst();
        actor.requireMemberId(number(record.get("assignee_member_id")));
        var summary = record.get("summary") == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(jsonObject(record.get("summary")));
        String fingerprint = hash(Map.of("expected_attempt", expectedAttempt));
        var savedRetries = new ArrayList<Map<String, Object>>();
        Object saved = summary.get("retry_idempotency");
        if (saved != null && !(saved instanceof List<?>)) throw new IllegalStateException("invalid retry idempotency data");
        if (saved instanceof List<?> entries) {
            for (Object item : entries) {
                Map<String, Object> entry = jsonObjectValue(item);
                Instant created = Instant.parse((String) entry.get("created_at"));
                if (created.isBefore(Instant.now().minusSeconds(24 * 60 * 60))) continue;
                savedRetries.add(entry);
                if (Long.toString(actor.accountId()).equals(entry.get("account_id")) && key.equals(entry.get("key"))) {
                    if (!fingerprint.equals(entry.get("fingerprint"))) {
                        throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 요청 키에 다른 내용이 전달되었습니다.");
                    }
                    return jsonObjectValue(entry.get("response"));
                }
            }
        }
        if (!"ERROR".equals(record.get("status")) || ((Number) record.get("current_attempt")).intValue() != expectedAttempt) {
            throw transition();
        }
        var errorFindings = jdbc.sql("""
                select f.resolution from validation_run r join validation_finding f on f.validation_run_id=r.id
                where r.validation_id=:id and r.attempt=:attempt and r.status in ('ERROR','TIMEOUT')
                  and f.validation_id=r.validation_id and f.source='MODEL' and f.kind='ERROR'
                """).param("id", validation).param("attempt", expectedAttempt).query(String.class).list();
        if (!retryable(errorFindings)) throw transition();
        Long currentBaseline = number(projects.getFirst().get("current_baseline_id"));
        if (currentBaseline == null || !currentBaseline.equals(number(record.get("baseline_id")))
                || !number(record.get("plan_revision_id")).equals(number(record.get("current_plan_revision_id")))) {
            throw new ApiException(HttpStatus.CONFLICT, "revision_conflict", "기준 설계 또는 작업 계획이 변경되었습니다. 새 검증이 필요합니다.");
        }
        long staleRefs = jdbc.sql("""
                select (select count(*) from validation_design_ref r
                    left join baseline_item bi on bi.baseline_id=:baseline and bi.design_item_id=r.design_item_id
                    where r.validation_id=:id and (bi.design_item_version_id is null or bi.design_item_version_id<>r.design_item_version_id))
                  + (select count(*) from validation_task_ref r left join task t on t.id=r.related_task_id
                    where r.validation_id=:id and (t.current_plan_revision_id is null or t.current_plan_revision_id<>r.related_plan_revision_id))
                """).param("baseline", currentBaseline).param("id", validation).query(Long.class).single();
        if (staleRefs > 0) throw new ApiException(HttpStatus.CONFLICT, "revision_conflict", "검증 참조가 변경되었습니다. 새 검증이 필요합니다.");
        var jobs = jdbc.sql("""
                update validation_job set status='QUEUED',lease_token=null,lease_until=null,next_run_at=now(),updated_at=now()
                where validation_id=:id and status='FAILED'
                """).param("id", validation).update();
        if (jobs != 1) throw transition();
        var requestActor = new LinkedHashMap<String, Object>();
        requestActor.put("account_id", caller.accountId()); requestActor.put("agent_token_id", caller.agentTokenId());
        requestActor.put("kind", caller.kind().name());
        summary.put("request_actor", requestActor);
        jdbc.sql("""
                update validation set status='RUNNING',current_attempt=current_attempt+1,finished_at=null,
                  stale_reason=null,summary=cast(:summary as jsonb),updated_at=now(),lock_version=lock_version+1
                where id=:id and status='ERROR' and current_attempt=:attempt
                """).param("summary", JSON.writeValueAsString(summary)).param("id", validation).param("attempt", expectedAttempt).update();
        Map<String, Object> response = get(caller, project, validation);
        savedRetries.add(Map.of("account_id", Long.toString(actor.accountId()), "key", key,
                "fingerprint", fingerprint, "created_at", Instant.now().toString(), "response", response));
        jdbc.sql("update validation set summary=summary || cast(:addition as jsonb) where id=:id")
                .param("addition", JSON.writeValueAsString(Map.of("retry_idempotency", savedRetries))).param("id", validation).update();
        return response;
    }

    private List<Map<String, Object>> rows(String sql, Map<String, ?> params) { return jdbc.sql(sql).params(params).query().listOfRows(); }
    private static Long number(Object o) { return o == null ? null : ((Number) o).longValue(); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message); }
    private static ApiException transition() { return new ApiException(HttpStatus.CONFLICT, "invalid_transition", "현재 실패 실행은 재시도할 수 없습니다."); }
    static boolean retryable(List<String> resolutions) {
        return !resolutions.isEmpty() && resolutions.stream().allMatch("RETRY"::equals);
    }
    private static Map<String, Object> jsonObject(Object value) {
        return JSON.readValue(value.toString(), new TypeReference<Map<String, Object>>() {});
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> jsonObjectValue(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("invalid saved validation response");
        return (Map<String, Object>) value;
    }
}
