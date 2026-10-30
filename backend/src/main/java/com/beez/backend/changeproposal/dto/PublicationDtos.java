package com.beez.backend.changeproposal.dto;

import com.beez.backend.changeproposal.dto.ProposalDtos.Proposal;
import com.beez.backend.designitem.dto.BaselineDocumentRefResponse;
import com.beez.backend.designitem.dto.BaselineItemRefResponse;
import java.util.List;

public final class PublicationDtos {
    public record ApproveRequest(String expectedBaseBaselineId, Integer expectedRevision, String expectedSnapshotHash) { }
    public record Baseline(String id, String projectId, int versionNo, List<BaselineDocumentRefResponse> documents,
                           List<BaselineItemRefResponse> items, String approvedBy, String createdAt) { }
    public record Publication(Proposal proposal, Baseline baseline) { }
    /** CREATE has no persisted ID until approval; proposalItemIndex identifies its submitted item. */
    public record PreviewItem(String designItemId, String title, int versionNo, String operation, Integer proposalItemIndex) { }
    public record Counts(int items, int documents) { }
    public record Unreviewed(int aiCandidates, int openReviewRequests) { }
    public record Preview(int nextBaselineVersionNo, List<PreviewItem> baselineItems,
                          List<StaleValidation> validationsToStale, List<WaitingTask> tasksToWait,
                          Counts unchanged, Unreviewed unreviewed) { }
    public record StaleValidation(String validationId, String taskId, String taskTitle) { }
    public record WaitingTask(String taskId, String title, String assigneeMemberId) { }
    public record ImpactResult(List<StaleValidation> staleValidations, List<WaitingTask> waitingTasks) {
        public static ImpactResult empty() { return new ImpactResult(List.of(), List.of()); }
    }
    public record ChangedItem(long designItemId, long previousVersionId, Long newVersionId, String operation) { }
    /** For preview the new baseline/version IDs are absent; previous IDs suffice to select affected validations. */
    public record ImpactRequest(Long baselineId, long previousBaselineId, long changeProposalId, int revision,
                                String snapshotHash, List<ChangedItem> changedItems, String reason) { }
    private PublicationDtos() { }
}
