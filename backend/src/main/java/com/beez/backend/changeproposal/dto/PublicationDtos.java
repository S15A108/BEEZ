package com.beez.backend.changeproposal.dto;

import com.beez.backend.changeproposal.dto.ProposalDtos.Proposal;
import com.beez.backend.designitem.dto.BaselineDocumentRefResponse;
import com.beez.backend.designitem.dto.BaselineItemRefResponse;
import java.util.List;

public final class PublicationDtos {
    /** impactOverrideReason: why the leader approves without a completed, current impact analysis (S15P31A108-241).
     *  A null reason is omitted from the canonical form so idempotency fingerprints of pre-241 requests stay valid. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record ApproveRequest(String expectedBaseBaselineId, Integer expectedRevision, String expectedSnapshotHash,
                                 String impactOverrideReason) { }
    public record Baseline(String id, String projectId, int versionNo, List<BaselineDocumentRefResponse> documents,
                           List<BaselineItemRefResponse> items, String approvedBy, String createdAt) { }
    public record Publication(Proposal proposal, Baseline baseline) { }
    /** CREATE has no persisted ID until approval; proposalItemIndex identifies its submitted item. */
    public record PreviewItem(String designItemId, String title, int versionNo, String operation, Integer proposalItemIndex) { }
    public record Counts(int items, int documents) { }
    /** aiCandidates is null unless the current analysis is trusted ("not evaluated" must not read as 0 candidates).
     *  ALWAYS keeps that null in the response despite the global non_null setting. */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
    public record Unreviewed(Integer aiCandidates, int openReviewRequests, AiAnalysis aiAnalysis, boolean requiresOverrideReason) { }
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record AiAnalysis(String status, boolean isCurrent, String errorCode, boolean reanalysisInProgress) { }
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
