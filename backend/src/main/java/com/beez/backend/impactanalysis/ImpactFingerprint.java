package com.beez.backend.impactanalysis;

import static com.beez.backend.designitem.support.DesignJson.hash;
import java.util.*;

/**
 * Freshness fingerprint of an analysis input. It contains only values that decide which tasks are
 * affected, so task churn (lock version, timestamps, validation progress) does not invalidate a result.
 * Changing what this covers changes when stored results become stale; keep it in sync with
 * {@link ExplicitImpactCalculator} and the relation/plan inputs of the analysis.
 */
final class ImpactFingerprint {
    private ImpactFingerprint() {}

    static String of(ImpactContextQuery.Snapshot snapshot) {
        var proposal = snapshot.proposal();
        var tasks = snapshot.taskReferences().stream().map(ref -> {
            var task = new LinkedHashMap<String, Object>();
            task.put("task", ref.taskId());
            // Only the open/closed distinction changes explicit impacts.
            task.put("closed", Set.of("COMPLETED", "CANCELLED").contains(ref.status()));
            task.put("assignee", ref.assigneeMemberId());
            task.put("plan_revision", ref.planRevisionId());
            task.put("design_item", ref.designItemId());
            task.put("design_item_version", ref.designItemVersionId());
            task.put("validation", ref.validationId());
            return task;
        }).sorted(Comparator.comparing(Object::toString)).toList();
        var approved = snapshot.approvedItems().stream()
                .map(item -> item.itemId() + ":" + item.versionId()).sorted().toList();
        var value = new LinkedHashMap<String, Object>();
        value.put("proposal", proposal.id());
        value.put("revision", proposal.revisionNo());
        value.put("snapshot_hash", proposal.snapshotHash());
        value.put("baseline", proposal.baselineId());
        value.put("relation_set_revision", snapshot.relationSetRevision());
        value.put("document_changes", snapshot.documentChanges());
        value.put("approved_items", approved);
        value.put("tasks", tasks);
        // The search bound can change the verdict without changing the returned rows (20 -> 21 matches).
        var search = snapshot.searchCoverage();
        value.put("search", Map.of("status", search.status().name(), "terms_truncated", search.termsTruncated(),
                "designs_truncated", search.designsTruncated(), "tasks_truncated", search.tasksTruncated(),
                "candidate_tasks", search.candidateTaskIds().stream().sorted().toList()));
        return hash(value);
    }
}
