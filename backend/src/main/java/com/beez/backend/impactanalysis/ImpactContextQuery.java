package com.beez.backend.impactanalysis;

import com.beez.backend.changeproposal.dto.ProposalDtos;
import com.beez.backend.designitem.support.DesignJson;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.impactanalysis.ImpactRelationReader.ConfirmedRelation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only, project-scoped inputs for analysis of a change proposal before publication.
 * The upstream proposal and persistence APIs are separate work; this service does not call an
 * LLM, make approval decisions, or mutate tasks. Callers must recheck a snapshot before storing
 * or displaying results after an asynchronous analysis.
 */
@Service
public class ImpactContextQuery {
    private final JdbcClient jdbc;
    private final ProjectAccessChecker access;
    private final ImpactRelationReader dependencies;

    public ImpactContextQuery(JdbcClient jdbc, ProjectAccessChecker access,
                              ImpactRelationReader dependencies) {
        this.jdbc = jdbc;
        this.access = access;
        this.dependencies = dependencies;
    }

    public record Proposal(long id, long projectId, long baselineId, int lockVersion,
                           String status, String reason, Instant updatedAt,
                           int revisionNo, String snapshotHash) {}
    /** Zero-based index is scoped to this proposal revision; it is not a persistent row ID. */
    public record Change(int proposalItemIndex, String operation, Long itemId, Long beforeVersionId,
                         String newKind, Integer schemaVersion, String proposedContent, Instant updatedAt) {}
    private record StoredProposal(Proposal proposal, ProposalDtos.Snapshot revision) {}
    public record ApprovedItem(long itemId, long versionId, String key, String kind,
                               int versionNo, int schemaVersion, String title, String content,
                               Long sourceDocumentId, Long sourceDocumentVersionId,
                               Integer sourceDocumentVersionNo, String sourceLocator) {}
    public record TaskReference(long taskId, String taskKey, String status, String waitingReason, int lockVersion,
                                Instant taskCreatedAt, Instant taskUpdatedAt,
                                Long assigneeMemberId, long planRevisionId, int revisionNo,
                                String title, String goal, String includedScope, String excludedScope,
                                String approach, String completionCriteria, String unlinked, String dependencyTaskIds,
                                boolean noContractChange, Long contextPacketId, long createdBy, Long agentTokenId, Instant createdAt,
                                Long designItemId, Long designItemVersionId, String relation, String proposedContent,
                                Long validationId, String validationStatus) {}
    public enum RuleCandidateStatus { NOT_CONNECTED }

    public record Snapshot(Proposal proposal, List<Change> changes, List<ApprovedItem> approvedItems,
                           List<ConfirmedRelation> dependencies, List<TaskReference> taskReferences,
                           RuleCandidateStatus ruleCandidateStatus, ImpactKeywordSearch.Coverage searchCoverage,
                           long relationSetRevision, boolean documentChanges) {
        public Snapshot {
            changes = List.copyOf(changes);
            approvedItems = List.copyOf(approvedItems);
            dependencies = List.copyOf(dependencies);
            taskReferences = List.copyOf(taskReferences);
        }

        // Existing callers that do not perform keyword search must not imply complete coverage.
        public Snapshot(Proposal proposal, List<Change> changes, List<ApprovedItem> approvedItems,
                        List<ConfirmedRelation> dependencies, List<TaskReference> taskReferences,
                        RuleCandidateStatus ruleCandidateStatus) {
            this(proposal, changes, approvedItems, dependencies, taskReferences, ruleCandidateStatus,
                    new ImpactKeywordSearch.Coverage(ImpactKeywordSearch.Status.NO_USABLE_TERMS, 0, false, false, false), 0, false);
        }

        public Snapshot(Proposal proposal, List<Change> changes, List<ApprovedItem> approvedItems,
                        List<ConfirmedRelation> dependencies, List<TaskReference> taskReferences,
                        RuleCandidateStatus ruleCandidateStatus, ImpactKeywordSearch.Coverage searchCoverage) {
            this(proposal, changes, approvedItems, dependencies, taskReferences, ruleCandidateStatus, searchCoverage, 0, false);
        }
    }

    /** A REPEATABLE READ snapshot prevents mixing data from multiple database revisions. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot load(CallerContext caller, long projectId, long proposalId) {
        access.requireMember(caller, projectId);
        // Analyze the immutable submitted revision, never the mutable item projection.
        // Legacy V10 hashes use PostgreSQL jsonb::text; new #195 snapshots use DesignJson.
        StoredProposal stored = jdbc.sql("""
                select c.id, c.project_id, c.base_baseline_id, c.lock_version, c.status, c.updated_at,
                       c.revision_no, p.current_baseline_id, r.base_baseline_id as revision_baseline_id,
                       r.snapshot_hash, cast(r.snapshot as text) as snapshot,
                       'sha256:' || encode(sha256(convert_to(r.snapshot::text,'UTF8')),'hex') as legacy_hash
                  from change_proposal c join project p on p.id = c.project_id
                  left join change_proposal_revision r on r.change_proposal_id=c.id
                       and r.project_id=c.project_id and r.revision_no=c.revision_no
                 where c.id=:proposal and c.project_id=:project and p.archived_at is null
                """).param("project", projectId).param("proposal", proposalId)
                .query((rs, row) -> {
                    Long currentBaseline = nullableLong(rs, "current_baseline_id");
                    long baseBaseline = rs.getLong("base_baseline_id");
                    if (currentBaseline == null || currentBaseline != baseBaseline) {
                        throw new ApiException(HttpStatus.CONFLICT, "baseline_changed", "변경 제안의 기준 설계를 다시 확인해야 합니다");
                    }
                    String raw = rs.getString("snapshot"), storedHash = rs.getString("snapshot_hash");
                    Long revisionBaseline = nullableLong(rs, "revision_baseline_id");
                    if (raw == null || storedHash == null || revisionBaseline == null || revisionBaseline != baseBaseline) {
                        throw invalidSnapshot();
                    }
                    final ProposalDtos.Snapshot revision;
                    try {
                        revision = DesignJson.JSON.readValue(raw, ProposalDtos.Snapshot.class);
                    } catch (RuntimeException malformed) {
                        throw invalidSnapshot();
                    }
                    if (revision.input() == null || revision.input().items() == null || revision.documents() == null
                            || !Long.toString(baseBaseline).equals(revision.input().baseBaselineId())) throw invalidSnapshot();
                    // New snapshots are hashed from the same canonical DTO used by #195. Legacy
                    // migration hashes jsonb::text, which must be verified by its own algorithm.
                    String calculated = revision.legacy() ? rs.getString("legacy_hash") : DesignJson.hash(revision);
                    if (!storedHash.equals(calculated)) throw invalidSnapshot();
                    var proposal = new Proposal(rs.getLong("id"), rs.getLong("project_id"), baseBaseline,
                            rs.getInt("lock_version"), rs.getString("status"), revision.input().reason(),
                            rs.getTimestamp("updated_at").toInstant(), rs.getInt("revision_no"), storedHash);
                    return new StoredProposal(proposal, revision);
                }).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "변경 제안을 찾을 수 없습니다"));
        Proposal proposal = stored.proposal();
        if (!Set.of("PROPOSED", "IN_REVIEW").contains(proposal.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "proposal_not_analyzable", "검토 중인 변경 제안이 아닙니다");
        }
        var submitted = stored.revision().input().items();
        boolean documentChanges = !stored.revision().documents().isEmpty()
                || (stored.revision().input().documents() != null && !stored.revision().input().documents().isEmpty());
        if (submitted.isEmpty() && !documentChanges) {
            throw new ApiException(HttpStatus.CONFLICT, "analysis_input_missing", "분석할 변경 항목이 없습니다");
        }
        Set<Long> submittedIds = new LinkedHashSet<>();
        for (var item : submitted) {
            Long itemId = parseOptionalId(item.designItemId());
            if (itemId != null) submittedIds.add(itemId);
        }
        Map<Long, Long> baselineVersions = new LinkedHashMap<>();
        if (!submittedIds.isEmpty()) {
            var rows = jdbc.sql("""
                    select design_item_id, design_item_version_id from baseline_item
                     where project_id=:project and baseline_id=:baseline and design_item_id in (:items)
                    """).param("project", projectId).param("baseline", proposal.baselineId())
                    .param("items", submittedIds).query().listOfRows();
            for (var row : rows) baselineVersions.put(((Number) row.get("design_item_id")).longValue(),
                    ((Number) row.get("design_item_version_id")).longValue());
        }
        List<Change> changes = new ArrayList<>();
        for (int index = 0; index < submitted.size(); index++) {
            var item = submitted.get(index);
            Long itemId = parseOptionalId(item.designItemId()), version = parseOptionalId(item.baseDesignItemVersionId());
            if ("CREATE".equals(item.operation())) {
                if (itemId != null || version != null) throw invalidSnapshot();
            } else if ("UPDATE".equals(item.operation()) || "RETIRE".equals(item.operation())) {
                if (itemId == null || version == null || !version.equals(baselineVersions.get(itemId))) {
                    throw new ApiException(HttpStatus.CONFLICT, "baseline_changed", "변경 항목의 기준 버전을 다시 확인해야 합니다");
                }
            } else throw invalidSnapshot();
            changes.add(new Change(index, item.operation(), itemId, version, item.kind(), item.schemaVersion(),
                    item.proposedContent() == null ? null : DesignJson.canonical(item.proposedContent()), proposal.updatedAt()));
        }

        Set<Long> changedIds = new LinkedHashSet<>();
        changes.stream().map(Change::itemId).filter(id -> id != null).forEach(changedIds::add);
        var relationSnapshot = dependencies.load(caller, projectId, proposal.baselineId(), changedIds);
        if (documentChanges) {
            // Document diffs are not yet in AI-A03; never infer no impact from a partial input.
            return new Snapshot(proposal, changes, List.of(), List.of(), List.of(), RuleCandidateStatus.NOT_CONNECTED,
                    new ImpactKeywordSearch.Coverage(ImpactKeywordSearch.Status.NO_USABLE_TERMS, 0, false, false, false),
                    relationSnapshot.relationSetRevision(), true);
        }
        List<ConfirmedRelation> confirmed = relationSnapshot.relations();
        Set<Long> relatedItemIds = new LinkedHashSet<>();
        relatedItemIds.addAll(changedIds);
        List<ConfirmedRelation> relevantDependencies = confirmed.stream()
                .filter(d -> changedIds.contains(d.toItemId())).toList();
        relevantDependencies.stream()
                .map(ConfirmedRelation::fromItemId).forEach(relatedItemIds::add);

        // Known graph relations are authoritative candidates. Keyword matches are separate,
        // bounded search inputs to the AI and must never create or confirm graph edges.
        List<ApprovedItem> graphApproved = loadApproved(projectId, proposal.baselineId(), relatedItemIds);
        if (graphApproved.size() != relatedItemIds.size()) {
            throw new ApiException(HttpStatus.CONFLICT, "baseline_inconsistent", "승인 설계 항목을 확인할 수 없습니다");
        }
        ImpactKeywordSearch.Terms terms = ImpactKeywordSearch.terms(proposal.reason(), changes, graphApproved);
        List<Long> designMatches = searchDesignIds(projectId, proposal.baselineId(), relatedItemIds, terms);
        boolean designsTruncated = designMatches.size() > ImpactKeywordSearch.MAX_RESULTS;
        List<Long> selectedDesigns = designMatches.subList(0, Math.min(designMatches.size(), ImpactKeywordSearch.MAX_RESULTS));
        List<ApprovedItem> approved = new java.util.ArrayList<>(graphApproved);
        approved.addAll(loadApproved(projectId, proposal.baselineId(), new LinkedHashSet<>(selectedDesigns)));

        List<Long> taskMatches = searchTaskIds(projectId, relatedItemIds, selectedDesigns, terms);
        boolean tasksTruncated = taskMatches.size() > ImpactKeywordSearch.MAX_RESULTS;
        List<Long> selectedTasks = taskMatches.subList(0, Math.min(taskMatches.size(), ImpactKeywordSearch.MAX_RESULTS));
        var searchCoverage = new ImpactKeywordSearch.Coverage(terms.status(), terms.values().size(),
                terms.termsTruncated(), designsTruncated, tasksTruncated, selectedTasks);

        Set<Long> graphIds = relatedItemIds.isEmpty() ? Set.of(-1L) : relatedItemIds;
        List<Long> semanticIds = selectedTasks.isEmpty() ? List.of(-1L) : selectedTasks;
        List<TaskReference> taskReferences = relatedItemIds.isEmpty() && selectedTasks.isEmpty() ? List.of() : jdbc.sql("""
                select t.id as task_id, t.key, t.status, t.waiting_reason, t.lock_version, t.assignee_member_id,
                       t.created_at as task_created_at, t.updated_at as task_updated_at,
                       rev.id as revision_id, rev.revision_no, rev.title, rev.goal,
                       cast(rev.included_scope as text) as included_scope, cast(rev.excluded_scope as text) as excluded_scope,
                       rev.approach, cast(rev.completion_criteria as text) as completion_criteria,
                       cast(rev.unlinked as text) as unlinked, cast(rev.dependency_task_ids as text) as dependency_task_ids,
                       rev.no_contract_change, rev.context_packet_id, rev.created_by_account_id, rev.created_by_token_id,
                       rev.created_at as revision_created_at,
                       ref.design_item_id, ref.design_item_version_id, ref.relation,
                       cast(ref.proposed_content as text) as ref_proposed_content,
                       latest_validation.id as validation_id, latest_validation.status as validation_status
                  from task t
                  join task_plan_revision rev on rev.id = t.current_plan_revision_id and rev.task_id = t.id
                  left join task_plan_design_ref ref on ref.plan_revision_id = rev.id
                  left join design_item ref_item on ref_item.id = ref.design_item_id and ref_item.project_id = :project
                  left join lateral (
                      select v.id, v.status from validation v
                       where v.task_id = t.id and v.plan_revision_id = rev.id
                       order by v.id desc limit 1
                  ) latest_validation on true
                 where t.project_id = :project and t.status not in ('COMPLETED', 'CANCELLED')
                   and (ref.design_item_id is null or ref_item.id is not null)
                   and (t.id in (:semanticTasks) or exists (
                       select 1 from task_plan_design_ref match_ref
                        where match_ref.plan_revision_id = rev.id and match_ref.design_item_id in (:items)))
                 order by t.id, ref.design_item_id
                """)
                .param("project", projectId).param("items", graphIds).param("semanticTasks", semanticIds)
                .query((rs, row) -> new TaskReference(rs.getLong("task_id"), rs.getString("key"),
                        rs.getString("status"), rs.getString("waiting_reason"), rs.getInt("lock_version"),
                        rs.getTimestamp("task_created_at").toInstant(), rs.getTimestamp("task_updated_at").toInstant(),
                        nullableLong(rs, "assignee_member_id"),
                        rs.getLong("revision_id"), rs.getInt("revision_no"), rs.getString("title"), rs.getString("goal"),
                        rs.getString("included_scope"), rs.getString("excluded_scope"), rs.getString("approach"),
                        rs.getString("completion_criteria"), rs.getString("unlinked"), rs.getString("dependency_task_ids"),
                        rs.getBoolean("no_contract_change"), nullableLong(rs, "context_packet_id"),
                        rs.getLong("created_by_account_id"), nullableLong(rs, "created_by_token_id"),
                        rs.getTimestamp("revision_created_at").toInstant(),
                        nullableLong(rs, "design_item_id"),
                        nullableLong(rs, "design_item_version_id"), rs.getString("relation"), rs.getString("ref_proposed_content"),
                        nullableLong(rs, "validation_id"), rs.getString("validation_status"))).list();

        // Rule candidate calculation belongs to the shared BE integration. Do not treat
        // its absence as an empty, completed evaluation or create duplicate rule results.
        return new Snapshot(proposal, changes, approved, relevantDependencies, taskReferences,
                RuleCandidateStatus.NOT_CONNECTED, searchCoverage, relationSnapshot.relationSetRevision(), false);
    }

    /**
     * Detect a change after the model call before a caller uses the output. This is not a lock:
     * #197 must also coordinate this check with its own result write transaction to prevent races.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public void requireUnchanged(CallerContext caller, Snapshot previous) {
        Snapshot current = load(caller, previous.proposal().projectId(), previous.proposal().id());
        if (!previous.equals(current)) {
            throw new ApiException(HttpStatus.CONFLICT, "analysis_input_changed", "분석 중 입력이 변경되어 재분석이 필요합니다");
        }
    }

    private List<ApprovedItem> loadApproved(long projectId, long baselineId, Set<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.sql("""
                select di.id as item_id, di.key, di.kind, v.id as version_id, v.version_no, v.schema_version, v.title,
                       cast(v.content as text) as content, dv.document_id, dv.id as document_version_id,
                       dv.version_no as document_version_no,
                       case when dv.id is not null then cast(v.source_locator as text) else null end as source_locator
                  from baseline_item bi
                  join design_item di on di.id = bi.design_item_id and di.project_id = bi.project_id
                  join design_item_version v on v.id = bi.design_item_version_id and v.design_item_id = di.id
                  left join baseline_document bd on bd.project_id = bi.project_id and bd.baseline_id = bi.baseline_id
                                                 and bd.document_version_id = v.source_document_version_id
                  left join document_version dv on dv.id = bd.document_version_id and dv.document_id = bd.document_id
                                               and dv.status = 'APPROVED'
                 where bi.project_id = :project and bi.baseline_id = :baseline and di.id in (:items)
                   and v.status = 'APPROVED'
                 order by di.id
                """)
                .param("project", projectId).param("baseline", baselineId).param("items", ids)
                .query((rs, row) -> new ApprovedItem(rs.getLong("item_id"), rs.getLong("version_id"),
                        rs.getString("key"), rs.getString("kind"), rs.getInt("version_no"), rs.getInt("schema_version"), rs.getString("title"),
                        rs.getString("content"), nullableLong(rs, "document_id"), nullableLong(rs, "document_version_id"),
                        nullableInt(rs, "document_version_no"), rs.getString("source_locator"))).list();
    }

    private List<Long> searchDesignIds(long projectId, long baselineId, Set<Long> excluded, ImpactKeywordSearch.Terms terms) {
        if (terms.status() != ImpactKeywordSearch.Status.SEARCHED) return List.of();
        String predicate = ImpactKeywordSearch.predicate("concat_ws(' ', v.title, cast(v.content as text))", terms.values().size());
        String sql = """
                select di.id
                  from baseline_item bi
                  join design_item di on di.id = bi.design_item_id and di.project_id = bi.project_id
                  join design_item_version v on v.id = bi.design_item_version_id and v.design_item_id = di.id
                 where bi.project_id = :project and bi.baseline_id = :baseline and v.status = 'APPROVED'
                   and di.id not in (:excluded) and
                """ + predicate + " order by di.id limit :limit";
        var statement = jdbc.sql(sql).param("project", projectId).param("baseline", baselineId)
                .param("excluded", excluded.isEmpty() ? Set.of(-1L) : excluded)
                .param("limit", ImpactKeywordSearch.MAX_RESULTS + 1);
        for (int i = 0; i < terms.values().size(); i++) statement = statement.param("term" + i, terms.values().get(i));
        return statement.query(Long.class).list();
    }

    private List<Long> searchTaskIds(long projectId, Set<Long> graphItems, List<Long> selectedDesigns,
                                     ImpactKeywordSearch.Terms terms) {
        if (terms.status() != ImpactKeywordSearch.Status.SEARCHED) return List.of();
        String predicate = ImpactKeywordSearch.predicate(
                "concat_ws(' ', rev.title, rev.goal, cast(rev.included_scope as text), cast(rev.excluded_scope as text), rev.approach, cast(rev.completion_criteria as text))",
                terms.values().size());
        String sql = """
                select t.id from task t
                  join task_plan_revision rev on rev.id = t.current_plan_revision_id and rev.task_id = t.id
                 where t.project_id = :project and t.status not in ('COMPLETED', 'CANCELLED')
                   and not exists (select 1 from task_plan_design_ref r
                                   where r.plan_revision_id = rev.id and r.design_item_id in (:graphItems))
                   and (
                """ + predicate + """
                   or exists (select 1 from task_plan_design_ref search_ref
                               where search_ref.plan_revision_id = rev.id
                                 and search_ref.design_item_id in (:selectedDesigns))
                ) order by t.id limit :limit
                """;
        var statement = jdbc.sql(sql).param("project", projectId)
                .param("graphItems", graphItems.isEmpty() ? Set.of(-1L) : graphItems)
                .param("selectedDesigns", selectedDesigns.isEmpty() ? List.of(-1L) : selectedDesigns)
                .param("limit", ImpactKeywordSearch.MAX_RESULTS + 1);
        for (int i = 0; i < terms.values().size(); i++) statement = statement.param("term" + i, terms.values().get(i));
        return statement.query(Long.class).list();
    }

    private static ApiException invalidSnapshot() {
        return new ApiException(HttpStatus.CONFLICT, "analysis_snapshot_invalid", "변경 제안 리비전을 다시 확인해야 합니다");
    }

    private static Long parseOptionalId(String value) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException malformed) {
            throw invalidSnapshot();
        }
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }
}
