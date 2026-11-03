package com.beez.backend.impactanalysis;

import com.beez.backend.changeproposal.dto.ProposalDtos;
import com.beez.backend.common.access.AccessErrors;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.designitem.support.DesignJson;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import static com.beez.backend.impactanalysis.OwnerRecommendationDtos.*;

/** Current-plan direct references only. The caller supplies a transaction and checks freshness at use. */
@Repository
class DirectOwnerQuery {
    private static final int MAX_REFERENCES = 1000;
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;

    DirectOwnerQuery(JdbcClient jdbc, ProjectAccessChecker access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private record Proposal(int revision, String hash, long baseline, Long currentBaseline,
                            Long publishedBaseline, String status, ProposalDtos.Snapshot snapshot) {}
    record TaskOwner(long taskId, String taskKey, long planRevisionId, long memberId, String area) {}

    void lockProject(CallerContext caller, long project) {
        access.requireMember(caller, project);
        boolean locked = jdbc.sql("SELECT id FROM project WHERE id=:id AND archived_at IS NULL FOR UPDATE")
                .param("id", project).query(Long.class).optional().isPresent();
        if (!locked) throw AccessErrors.notFound("owner_recommendation_project_missing");
    }

    DirectResult load(CallerContext caller, long project, long proposalId) {
        access.requireMember(caller, project);
        Proposal proposal = readProposal(project, proposalId);
        if (!Set.of("PROPOSED", "IN_REVIEW", "PUBLISHED").contains(proposal.status())) {
            return empty(proposal, "NOT_APPLICABLE", "proposal_not_active");
        }
        Long expectedBaseline = "PUBLISHED".equals(proposal.status()) ? proposal.publishedBaseline() : proposal.baseline();
        if (expectedBaseline == null || !expectedBaseline.equals(proposal.currentBaseline())) {
            return empty(proposal, "STALE", "baseline_changed");
        }

        Set<Long> changedItems = new LinkedHashSet<>();
        Map<Long, Long> expectedVersions = new LinkedHashMap<>();
        for (var item : proposal.snapshot().input().items()) {
            if (item == null) throw invalidSnapshot();
            Long itemId = id(item.designItemId()), versionId = id(item.baseDesignItemVersionId());
            if ("CREATE".equals(item.operation())) {
                if (itemId != null || versionId != null) throw invalidSnapshot();
            } else if ("UPDATE".equals(item.operation()) || "RETIRE".equals(item.operation())) {
                if (itemId == null || versionId == null || expectedVersions.put(itemId, versionId) != null) throw invalidSnapshot();
                changedItems.add(itemId);
            } else throw invalidSnapshot();
        }
        validateBaselineItems(project, proposal.baseline(), expectedVersions);

        Map<Long, Long> documents = new LinkedHashMap<>();
        for (var doc : proposal.snapshot().documents()) {
            if (doc == null) throw invalidSnapshot();
            Long document = id(doc.documentId()), version = id(doc.baseDocumentVersionId());
            if (document == null || version == null || documents.put(document, version) != null) throw invalidSnapshot();
        }
        var submittedDocuments = proposal.snapshot().input().documents();
        if (submittedDocuments != null) {
            Map<Long, Long> submitted = new LinkedHashMap<>();
            for (var doc : submittedDocuments) {
                if (doc == null) throw invalidSnapshot();
                Long document = id(doc.documentId()), version = id(doc.baseDocumentVersionId());
                if (document == null || version == null || submitted.put(document, version) != null) throw invalidSnapshot();
            }
            if (!submitted.equals(documents)) throw invalidSnapshot();
        }
        if (changedItems.isEmpty() && documents.isEmpty()
                && proposal.snapshot().input().items().isEmpty()) throw invalidSnapshot();

        var incomplete = new ArrayList<String>();
        if (!documents.isEmpty()) {
            var documentRows = jdbc.sql("""
                    SELECT bd.document_id, bd.document_version_id, EXISTS (
                        SELECT 1 FROM baseline_item bi
                        JOIN design_item_version dv ON dv.id=bi.design_item_version_id
                        WHERE bi.project_id=bd.project_id AND bi.baseline_id=bd.baseline_id
                          AND dv.source_document_version_id=bd.document_version_id
                    ) AS mapped
                    FROM baseline_document bd
                    WHERE bd.project_id=:project AND bd.baseline_id=:baseline AND bd.document_id IN (:documents)
                    """).param("project", project).param("baseline", proposal.baseline())
                    .param("documents", documents.keySet()).query().listOfRows();
            if (documentRows.size() != documents.size()) throw invalidSnapshot();
            for (var row : documentRows) {
                long document = ((Number) row.get("document_id")).longValue();
                long version = ((Number) row.get("document_version_id")).longValue();
                if (!Long.valueOf(version).equals(documents.get(document))) throw invalidSnapshot();
                if (!Boolean.TRUE.equals(row.get("mapped")) && !incomplete.contains("document_mapping_unavailable")) {
                    incomplete.add("document_mapping_unavailable");
                }
            }
        }

        var rows = jdbc.sql("""
                SELECT DISTINCT m.id AS member_id, m.area, t.id AS task_id, t.key AS task_key,
                       t.current_plan_revision_id AS plan_revision_id, ref.design_item_id,
                       ref.design_item_version_id, bi.design_item_version_id AS baseline_item_version_id,
                       bd.document_id, bd.document_version_id
                  FROM baseline_item bi
                  JOIN design_item_version dv ON dv.id=bi.design_item_version_id
                  LEFT JOIN baseline_document bd ON bd.project_id=bi.project_id AND bd.baseline_id=bi.baseline_id
                                               AND bd.document_version_id=dv.source_document_version_id
                  JOIN task_plan_design_ref ref ON ref.design_item_id=bi.design_item_id
                  JOIN task t ON t.current_plan_revision_id=ref.plan_revision_id AND t.project_id=bi.project_id
                  JOIN project_member m ON m.id=t.assignee_member_id AND m.project_id=t.project_id AND m.active
                  JOIN account a ON a.id=m.account_id AND a.deleted_at IS NULL
                 WHERE bi.project_id=:project AND bi.baseline_id=:baseline
                   AND (bi.design_item_id IN (:items) OR bd.document_id IN (:documents))
                   AND t.status NOT IN ('COMPLETED','CANCELLED')
                 ORDER BY m.id,t.id,ref.design_item_id,ref.design_item_version_id
                 LIMIT :limit
                """).param("project", project).param("baseline", proposal.baseline())
                .param("items", changedItems.isEmpty() ? Set.of(-1L) : changedItems)
                .param("documents", documents.isEmpty() ? Set.of(-1L) : documents.keySet())
                .param("limit", MAX_REFERENCES + 1)
                .query((rs, row) -> new DirectRow(rs.getLong("member_id"), rs.getString("area"),
                        rs.getLong("task_id"), rs.getString("task_key"), rs.getLong("plan_revision_id"),
                        rs.getLong("design_item_id"), rs.getLong("baseline_item_version_id"),
                        nullableLong(rs, "design_item_version_id"),
                        nullableLong(rs, "document_id"), nullableLong(rs, "document_version_id"))).list();
        if (rows.size() > MAX_REFERENCES) incomplete.add("direct_results_truncated");

        Map<Long, MutableOwner> owners = new LinkedHashMap<>();
        for (var row : rows.subList(0, Math.min(rows.size(), MAX_REFERENCES))) {
            var owner = owners.computeIfAbsent(row.memberId(), ignored -> new MutableOwner(row.area()));
            var evidence = new Evidence("DIRECT_TASK_REFERENCE", "현재 작업 계획이 변경 설계 항목을 직접 참조합니다.",
                    text(row.taskId()), row.taskKey(), text(row.planRevisionId()), text(row.itemId()),
                    text(row.baselineVersionId()), text(row.referencedVersionId()), text(row.documentId()),
                    text(row.documentVersionId()), List.of());
            if (!owner.evidence.contains(evidence)) owner.evidence.add(evidence);
        }
        var recommendations = new ArrayList<Recommendation>();
        owners.forEach((member, owner) -> recommendations.add(new Recommendation(text(member), owner.area, owner.evidence)));
        var recipients = incomplete.isEmpty() ? owners.keySet().stream().map(DirectOwnerQuery::text).toList() : List.<String>of();
        return new DirectResult(proposal.revision(), proposal.hash(), incomplete.isEmpty() ? "COMPLETE" : "INCOMPLETE",
                incomplete, recommendations, recipients);
    }

    Map<Long, TaskOwner> activeTaskOwners(long project, Set<Long> taskIds) {
        if (taskIds.isEmpty()) return Map.of();
        var rows = jdbc.sql("""
                SELECT t.id AS task_id,t.key AS task_key,t.current_plan_revision_id,m.id AS member_id,m.area
                FROM task t JOIN project_member m ON m.id=t.assignee_member_id AND m.project_id=t.project_id AND m.active
                JOIN account a ON a.id=m.account_id AND a.deleted_at IS NULL
                WHERE t.project_id=:project AND t.id IN (:tasks) AND t.current_plan_revision_id IS NOT NULL
                  AND t.status NOT IN ('COMPLETED','CANCELLED')
                """).param("project", project).param("tasks", taskIds)
                .query((rs, row) -> new TaskOwner(rs.getLong("task_id"), rs.getString("task_key"),
                        rs.getLong("current_plan_revision_id"), rs.getLong("member_id"), rs.getString("area"))).list();
        Map<Long, TaskOwner> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.taskId(), row));
        return result;
    }

    private Proposal readProposal(long project, long proposal) {
        return jdbc.sql("""
                SELECT c.revision_no,c.base_baseline_id,c.published_baseline_id,c.status,p.current_baseline_id,
                       r.base_baseline_id AS revision_baseline_id,r.snapshot_hash,cast(r.snapshot AS text) AS snapshot,
                       'sha256:' || encode(sha256(convert_to(r.snapshot::text,'UTF8')),'hex') AS legacy_hash
                FROM change_proposal c JOIN project p ON p.id=c.project_id
                LEFT JOIN change_proposal_revision r ON r.change_proposal_id=c.id AND r.project_id=c.project_id
                                                     AND r.revision_no=c.revision_no
                WHERE c.project_id=:project AND c.id=:proposal AND p.archived_at IS NULL
                """).param("project", project).param("proposal", proposal).query((rs, row) -> {
                    String raw = rs.getString("snapshot"), hash = rs.getString("snapshot_hash");
                    Long revisionBaseline = nullableLong(rs, "revision_baseline_id");
                    long baseline = rs.getLong("base_baseline_id");
                    if (raw == null || hash == null || revisionBaseline == null || revisionBaseline != baseline) throw invalidSnapshot();
                    final ProposalDtos.Snapshot snapshot;
                    try { snapshot = DesignJson.JSON.readValue(raw, ProposalDtos.Snapshot.class); }
                    catch (RuntimeException malformed) { throw invalidSnapshot(); }
                    if (snapshot.input() == null || snapshot.input().items() == null || snapshot.documents() == null
                            || !Long.toString(baseline).equals(snapshot.input().baseBaselineId())) throw invalidSnapshot();
                    String calculated = snapshot.legacy() ? rs.getString("legacy_hash") : DesignJson.hash(snapshot);
                    if (!hash.equals(calculated)) throw invalidSnapshot();
                    return new Proposal(rs.getInt("revision_no"), hash, baseline, nullableLong(rs, "current_baseline_id"),
                            nullableLong(rs, "published_baseline_id"), rs.getString("status"), snapshot);
                }).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "변경 제안을 찾을 수 없습니다"));
    }

    private void validateBaselineItems(long project, long baseline, Map<Long, Long> expected) {
        if (expected.isEmpty()) return;
        var rows = jdbc.sql("""
                SELECT design_item_id,design_item_version_id FROM baseline_item
                 WHERE project_id=:project AND baseline_id=:baseline AND design_item_id IN (:items)
                """).param("project", project).param("baseline", baseline).param("items", expected.keySet()).query().listOfRows();
        if (rows.size() != expected.size()) throw invalidSnapshot();
        for (var row : rows) {
            long item = ((Number) row.get("design_item_id")).longValue();
            long version = ((Number) row.get("design_item_version_id")).longValue();
            if (!Long.valueOf(version).equals(expected.get(item))) throw invalidSnapshot();
        }
    }

    private static DirectResult empty(Proposal proposal, String status, String reason) {
        return new DirectResult(proposal.revision(), proposal.hash(), status, List.of(reason), List.of(), List.of());
    }

    private record DirectRow(long memberId, String area, long taskId, String taskKey, long planRevisionId,
                             long itemId, long baselineVersionId, Long referencedVersionId,
                             Long documentId, Long documentVersionId) {}
    private static class MutableOwner {
        final String area;
        final List<Evidence> evidence = new ArrayList<>();
        MutableOwner(String area) { this.area = area; }
    }
    private static String text(Long value) { return value == null ? null : value.toString(); }
    private static Long id(String value) {
        if (value == null) return null;
        try { long n = Long.parseLong(value); if (n > 0) return n; }
        catch (NumberFormatException ignored) { }
        throw invalidSnapshot();
    }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }
    private static ApiException invalidSnapshot() {
        return new ApiException(HttpStatus.CONFLICT, "analysis_snapshot_invalid", "변경 제안 리비전을 다시 확인해야 합니다");
    }
}
