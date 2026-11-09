package com.beez.backend.bee.service;

import static com.beez.backend.designitem.support.DesignJson.JSON;
import static com.beez.backend.designitem.support.DesignJson.canonical;
import static com.beez.backend.designitem.support.DesignJson.object;

import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.repository.BaselineRepository;
import com.beez.backend.designitem.repository.DesignItemRepository;
import com.beez.backend.document.config.DocumentProperties;
import com.beez.backend.task.service.ProjectDesignLookup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Project-scoped snapshots before model calls and a locked recheck before issuing an answer. */
@Service
public class BeeQuestionSnapshot {
    private final ProjectAccessChecker access;
    private final ProjectDesignLookup design;
    private final BaselineRepository baselines;
    private final DesignItemRepository items;
    private final DocumentProperties properties;
    private final JdbcClient jdbc;

    public BeeQuestionSnapshot(ProjectAccessChecker access, ProjectDesignLookup design, BaselineRepository baselines,
                               DesignItemRepository items, DocumentProperties properties, JdbcClient jdbc) {
        this.access = access; this.design = design; this.baselines = baselines;
        this.items = items; this.properties = properties; this.jdbc = jdbc;
    }

    public record Snapshot(long baseline, String profile, List<String> versions,
                           List<Map<String, Object>> indexGenerations,
                           List<Map<String, Object>> approvedItems, List<Map<String, Object>> tasks,
                           List<String> unresolved) { }

    @Transactional(readOnly = true)
    public void authorize(CallerContext caller, long project) {
        access.requireMember(caller, project);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot capture(CallerContext caller, long project, Long taskId) {
        access.requireMember(caller, project);
        long baseline = currentBaseline(project);
        String profile = properties.embeddingProfile();
        if (profile == null || profile.isBlank() || baselines.countDocumentsWithoutReadyIndex(baseline, profile) != 0) {
            throw conflict("index_not_ready");
        }
        var versions = jdbc.sql("SELECT document_version_id FROM baseline_document WHERE baseline_id=:b ORDER BY document_version_id")
                .param("b", baseline).query(Long.class).list().stream().map(String::valueOf).toList();
        if (versions.size() > 1000) throw invalid("검색 범위가 너무 큽니다.");
        var generations = jdbc.sql("""
                SELECT m.document_version_id,m.attempt,m.text_hash,m.embedding_profile_id,
                       m.chunking_profile_id,m.parser_version,m.chunk_count,m.published_at
                  FROM baseline_document b JOIN document_index_manifest m ON m.document_version_id=b.document_version_id
                 WHERE b.baseline_id=:b AND m.embedding_profile_id=:profile ORDER BY m.document_version_id
                """).params(Map.of("b", baseline, "profile", profile)).query().listOfRows();
        if (generations.size() != versions.size()) throw conflict("index_not_ready");
        var rows = items.findPage(project, "APPROVED", null, null, 0, 501, baseline);
        if (rows.size() > 500) throw invalid("승인된 설계 항목이 너무 많습니다.");
        var approved = rows.stream().map(BeeQuestionSnapshot::item).toList();
        boolean sourceOmitted = false;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).get("source_document_version_id") != null && !approved.get(i).containsKey("source")) sourceOmitted = true;
        }
        List<String> unresolved = sourceOmitted ? List.of("일부 설계 항목의 출처 위치가 없거나 너무 길어 출처를 전달하지 못했습니다.") : List.of();
        return new Snapshot(baseline, profile, versions, generations, approved,
                taskId == null ? List.of() : List.of(task(project, taskId)), unresolved);
    }

    private long currentBaseline(long project) {
        return jdbc.sql("SELECT current_baseline_id FROM project WHERE id=:p AND current_baseline_id IS NOT NULL")
                .param("p", project).query(Long.class).optional().orElseThrow(() -> conflict("index_not_ready"));
    }

    private Map<String, Object> task(long project, long task) {
        var rows = jdbc.sql("""
                SELECT t.id,t.key,t.status,t.assignee_member_id,r.id AS revision_id,r.title,r.goal
                FROM task t LEFT JOIN task_plan_revision r ON r.id=t.current_plan_revision_id AND r.task_id=t.id
                WHERE t.project_id=:p AND t.id=:t
                """).params(Map.of("p", project, "t", task)).query().listOfRows();
        if (rows.isEmpty()) throw AccessErrors.notFound("bee_task_not_in_project");
        var row = rows.getFirst(); var result = new LinkedHashMap<String, Object>();
        result.put("id", row.get("id").toString()); result.put("key", row.get("key")); result.put("status", row.get("status"));
        if (row.get("assignee_member_id") != null) result.put("assignee_member_id", row.get("assignee_member_id").toString());
        if (row.get("revision_id") != null) {
            var plan = new LinkedHashMap<String, Object>(); plan.put("title", row.get("title"));
            if (row.get("goal") != null) plan.put("goal", row.get("goal"));
            result.put("current_plan_revision", Map.of("id", row.get("revision_id").toString(), "plan", plan));
        }
        return result;
    }

    private static Map<String, Object> item(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        for (String field : List.of("id", "design_item_id")) result.put(field, row.get(field).toString());
        for (String field : List.of("key", "kind", "version_no", "status", "schema_version", "content_hash")) result.put(field, row.get(field));
        result.put("content", object(row.get("content")));
        if (row.get("source_document_version_id") != null) {
            var locator = row.get("source_locator") == null ? Map.<String, Object>of() : object(row.get("source_locator"));
            String location = Objects.toString(locator.get("location"), locator.isEmpty() ? "" : canonical(locator));
            if (!location.isBlank() && location.codePointCount(0, location.length()) <= 1000) result.put("source", Map.of("document_id", row.get("source_document_id").toString(),
                    "document_version_id", row.get("source_document_version_id").toString(),
                    "version_no", row.get("source_version_no"), "location", location));
        }
        return result;
    }

    /** Reserve before any external call. Keys remain used even when a response is lost. */
    @Transactional
    public void reserve(CallerContext caller, long project, String keyHash) {
        if (!design.lockProject(project)) throw AccessErrors.notFound("bee_project_missing");
        access.requireMember(caller, project);
        int inserted = jdbc.sql("""
                INSERT INTO bee_question_request_key(project_id,account_id,key_hash)
                VALUES(:p,:a,:key) ON CONFLICT DO NOTHING
                """).params(Map.of("p", project, "a", caller.accountId(), "key", keyHash)).update();
        if (inserted != 1) throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict",
                "이미 사용한 질문 요청 키입니다. 답변을 재생할 수 없으므로 새 키로 요청해 주세요.");
    }

    /** Re-read complete chunk metadata only from the fixed, authorized baseline and profile. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> evidence(CallerContext caller, long project, Snapshot snapshot, List<Long> ids) {
        access.requireMember(caller, project);
        if (currentBaseline(project) != snapshot.baseline()) throw conflict("baseline_changed");
        if (ids.isEmpty()) return List.of();
        var rows = jdbc.sql("""
                SELECT c.id,c.chunk_key,c.text,c.locator::text AS locator,d.id AS document_id,d.title,
                       v.id AS document_version_id,v.version_no
                  FROM baseline_document b JOIN document d ON d.id=b.document_id AND d.project_id=:p
                  JOIN document_version v ON v.id=b.document_version_id AND v.document_id=d.id
                  JOIN document_index_manifest m ON m.document_version_id=v.id AND m.embedding_profile_id=:profile
                  JOIN document_chunk c ON c.document_version_id=v.id
                 WHERE b.baseline_id=:b AND c.id IN (:ids)
                """).params(Map.of("p", project, "b", snapshot.baseline(), "profile", snapshot.profile(), "ids", ids))
                .query().listOfRows();
        var byId = new HashMap<Long, Map<String, Object>>();
        for (var row : rows) {
            if (row.get("chunk_key") == null || row.get("locator") == null) throw conflict("index_not_ready");
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("evidence_id", row.get("id").toString());
            for (String field : List.of("document_id", "document_version_id", "chunk_key")) evidence.put(field, row.get(field).toString());
            for (String field : List.of("version_no", "text", "title")) evidence.put(field, row.get(field));
            evidence.put("locator", JSON.readValue(row.get("locator").toString(), Map.class));
            evidence.put("embedding_profile_id", snapshot.profile());
            byId.put(((Number) row.get("id")).longValue(), evidence);
        }
        if (byId.size() != ids.size()) throw conflict("baseline_changed");
        var ordered = new ArrayList<Map<String, Object>>();
        for (long id : ids) ordered.add(byId.get(id));
        return ordered;
    }

    @Transactional
    public long issue(CallerContext caller, long project, Long taskId, Snapshot before, List<Long> evidenceIds,
                      List<Map<String, Object>> expectedEvidence, List<String> usedVersions) {
        if (!design.lockProject(project)) throw AccessErrors.notFound("bee_project_missing");
        // Index publication locks these rows before replacing chunks. Hold compatible
        // read locks until answer issuance so a reindex cannot change evidence midway.
        jdbc.sql("""
                SELECT p.document_version_id FROM document_processing p
                JOIN baseline_document b ON b.document_version_id=p.document_version_id
                WHERE b.baseline_id=:b ORDER BY p.document_version_id FOR SHARE OF p
                """).param("b", before.baseline()).query(Long.class).list();
        Snapshot after = capture(caller, project, taskId);
        if (!before.equals(after)) throw conflict("baseline_changed");
        if (!expectedEvidence.equals(evidence(caller, project, before, evidenceIds))) throw conflict("index_not_ready");
        if (!before.versions().containsAll(usedVersions)) throw invalid("근거가 검색 범위와 다릅니다.");
        return jdbc.sql("""
                INSERT INTO bee_answer(project_id,account_id,mode,document_version_ids,baseline_id)
                VALUES(:p,:a,'APPROVED',cast(:versions AS jsonb),:b) RETURNING id
                """).params(Map.of("p", project, "a", caller.accountId(), "versions", JSON.writeValueAsString(usedVersions), "b", before.baseline()))
                .query(Long.class).single();
    }

    static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", message); }
    static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT, code, "검색 기준이 변경됐거나 아직 준비되지 않았습니다. 다시 시도해 주세요."); }
}
