package com.beez.backend.impactanalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/** Build the draft AI-A03 request using only the authorized database snapshot. */
public final class ImpactRequestAssembler {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ImpactRequestAssembler() {}

    public static Map<String, Object> assemble(ImpactContextQuery.Snapshot snapshot, String requestKey) {
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 256) {
            throw new IllegalArgumentException("Invalid request key");
        }
        var proposal = snapshot.proposal();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("project_id", id(proposal.projectId()));
        body.put("proposal_id", id(proposal.id()));
        body.put("request_key", requestKey);
        // Proposal reason is a provisional input extension; it is not approved design content.
        body.put("reason", proposal.reason());
        // Lexical search is bounded and cannot prove project-wide completeness.
        var coverage = snapshot.searchCoverage();
        body.put("search_context", Map.of(
                "method", "KEYWORD_AND_DESIGN_REFERENCE",
                "candidate_task_ids", coverage.candidateTaskIds().stream().map(ImpactRequestAssembler::id).toList(),
                "status", coverage.status().name(),
                "term_count", coverage.termCount(),
                "terms_truncated", coverage.termsTruncated(),
                "designs_truncated", coverage.designsTruncated(),
                "tasks_truncated", coverage.tasksTruncated(),
                "graph_coverage", "UNKNOWN"));

        List<Map<String, Object>> changes = new ArrayList<>();
        for (var change : snapshot.changes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("operation", change.operation());
            if (change.itemId() != null) item.put("design_item_id", id(change.itemId()));
            if (change.beforeVersionId() != null) item.put("base_design_item_version_id", id(change.beforeVersionId()));
            if (change.newKind() != null) item.put("kind", change.newKind());
            if (change.proposedContent() != null) item.put("proposed_content", object(change.proposedContent()));
            // V2 does not persist a proposed schema version; do not invent one.
            changes.add(item);
        }
        body.put("changes", changes);

        List<Map<String, Object>> approved = new ArrayList<>();
        for (var design : snapshot.approvedItems()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id(design.versionId()));
            item.put("design_item_id", id(design.itemId()));
            item.put("key", design.key());
            item.put("kind", design.kind());
            item.put("version_no", design.versionNo());
            item.put("schema_version", design.schemaVersion());
            item.put("status", "APPROVED");
            item.put("title", design.title());
            item.put("content", object(design.content()));
            source(design).ifPresent(value -> item.put("source", value));
            approved.add(item);
        }
        body.put("approved_items", approved);

        List<Map<String, Object>> explicit = new ArrayList<>();
        for (var dependency : snapshot.dependencies()) {
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("from_design_item_id", id(dependency.fromItemId()));
            relation.put("to_design_item_id", id(dependency.toItemId()));
            relation.put("origin", "EXPLICIT");
            relation.put("from_version_id", id(dependency.fromVersionId()));
            relation.put("to_version_id", id(dependency.toVersionId()));
            relation.put("confirmation_event_id", id(dependency.eventId()));
            relation.put("reason", dependency.reason());
            explicit.add(relation);
        }
        body.put("explicit_dependencies", explicit);

        Map<Long, Map<String, Object>> tasks = new LinkedHashMap<>();
        for (var ref : snapshot.taskReferences()) {
            Map<String, Object> task = tasks.get(ref.taskId());
            if (task == null) {
                task = task(snapshot.proposal().projectId(), ref);
                tasks.put(ref.taskId(), task);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> revision = (Map<String, Object>) task.get("current_plan_revision");
            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) revision.get("plan");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> refs = (List<Map<String, Object>>) plan.get("design_refs");
            if (ref.designItemId() != null && ref.designItemVersionId() != null) {
                Map<String, Object> designRef = new LinkedHashMap<>();
                designRef.put("design_item_id", id(ref.designItemId()));
                designRef.put("design_item_version_id", id(ref.designItemVersionId()));
                designRef.put("relation", ref.relation());
                if (ref.proposedContent() != null) designRef.put("proposed_content", object(ref.proposedContent()));
                refs.add(designRef);
            }
        }
        body.put("open_tasks", List.copyOf(tasks.values()));
        return body;
    }

    private static Map<String, Object> task(long projectId, ImpactContextQuery.TaskReference ref) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", id(ref.taskId()));
        task.put("project_id", id(projectId));
        if (ref.assigneeMemberId() != null) task.put("assignee_member_id", id(ref.assigneeMemberId()));
        task.put("key", ref.taskKey());
        task.put("status", ref.status());
        if (ref.waitingReason() != null) task.put("waiting_reason", ref.waitingReason());
        task.put("lock_version", ref.lockVersion());
        task.put("created_at", ref.taskCreatedAt().toString());
        task.put("updated_at", ref.taskUpdatedAt().toString());
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("title", ref.title());
        plan.put("goal", ref.goal());
        plan.put("included_scope", array(ref.includedScope()));
        plan.put("excluded_scope", array(ref.excludedScope()));
        if (ref.approach() != null) plan.put("approach", ref.approach());
        plan.put("completion_criteria", array(ref.completionCriteria()));
        plan.put("unlinked", array(ref.unlinked()));
        plan.put("dependency_task_ids", array(ref.dependencyTaskIds()));
        plan.put("no_contract_change", ref.noContractChange());
        if (ref.contextPacketId() != null) plan.put("context_packet_id", id(ref.contextPacketId()));
        plan.put("design_refs", new ArrayList<Map<String, Object>>());
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("id", id(ref.planRevisionId()));
        revision.put("task_id", id(ref.taskId()));
        revision.put("revision_no", ref.revisionNo());
        revision.put("created_by", id(ref.createdBy()));
        if (ref.agentTokenId() != null) revision.put("agent_token_id", id(ref.agentTokenId()));
        revision.put("created_at", ref.createdAt().toString());
        revision.put("plan", plan);
        task.put("current_plan_revision", revision);
        task.put("validation_status", ref.validationStatus() != null ? ref.validationStatus() : "NOT_RUN");
        if (ref.validationId() != null && ref.validationStatus() != null) {
            task.put("validation", Map.of("id", id(ref.validationId()), "status", ref.validationStatus()));
        }
        return task;
    }

    private static Optional<Map<String, Object>> source(ImpactContextQuery.ApprovedItem item) {
        if (item.sourceDocumentId() == null || item.sourceDocumentVersionId() == null
                || item.sourceDocumentVersionNo() == null || item.sourceLocator() == null) {
            return Optional.empty();
        }
        Object parsed = JSON.readValue(item.sourceLocator(), Object.class);
        if (!(parsed instanceof Map<?, ?> locator)) return Optional.empty();
        Object location = locator.get("location");
        if (!(location instanceof String text) || text.isBlank()) {
            Object start = locator.get("start_line"), end = locator.get("end_line");
            if ("text_lines".equals(locator.get("type")) && start instanceof Number a && end instanceof Number b
                    && a.longValue() > 0 && b.longValue() >= a.longValue()) {
                location = "lines " + a.longValue() + "-" + b.longValue();
            } else {
                return Optional.empty();
            }
        }
        return Optional.of(Map.of("document_id", id(item.sourceDocumentId()),
                "document_version_id", id(item.sourceDocumentVersionId()),
                "version_no", item.sourceDocumentVersionNo(), "location", location));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String json) {
        Object parsed = JSON.readValue(json, Object.class);
        if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) parsed;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(String json) {
        Object parsed = JSON.readValue(json, Object.class);
        if (!(parsed instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) parsed;
    }

    private static String id(long id) { return Long.toString(id); }
}
