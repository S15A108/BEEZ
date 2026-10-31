package com.beez.backend.changeproposal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** Public JSON uses the application's snake_case convention; IDs are decimal strings. */
public final class ProposalDtos {
    public record Item(String operation, String designItemId, String baseDesignItemVersionId,
                       String kind, Integer schemaVersion, Map<String, Object> proposedContent) { }
    public record DocumentChange(String documentId, String baseDocumentVersionId,
                                 String afterContent, String proposedDocumentVersionId) { }
    public record Input(String baseBaselineId, String title, String reason, String taskId, String validationId,
                        List<Item> items, List<DocumentChange> documents,
                        Integer expectedRevision, String expectedSnapshotHash, String previousProposalId) { }
    public record WithdrawRequest(Integer expectedRevision, String expectedSnapshotHash) { }
    public record RejectRequest(String reason, Integer expectedRevision, String expectedSnapshotHash) { }
    /** CHG-A11: the approval that produced the published baseline. */
    public record ApprovalRecord(String id, String proposalId, String approvedBy, String approvedAt, String baselineId) { }
    public record ResolvedDocument(String documentId, String baseDocumentVersionId,
                                   String proposedDocumentVersionId, String afterContent, String contentHash) { }
    public record Snapshot(Input input, List<ResolvedDocument> documents, boolean legacy) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Proposal(String id, String key, String projectId, String baseBaselineId, String title,
                           String reason, String taskId, String validationId, List<Item> items,
                           List<ResolvedDocument> documents, String status, String proposerAccountId,
                           String actorType, String currentBaselineId, boolean requiresRefresh,
                           String publishedBaselineId, int revision, String snapshotHash,
                           String createdAt, String updatedAt, String rejectionReason,
                           String previousProposalId, String withdrawnBy, String withdrawnAt) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Summary(String id, String key, String title, String status, int revision,
                          String baseBaselineId, String proposerAccountId, String updatedAt) { }
    public record Page(List<Summary> items, boolean hasMore, String nextCursor) { }
    public record Comparison(String targetType, String targetId, String format, Object baseContent,
                             Object currentContent, Object proposedContent, String diff,
                             boolean currentChanged) { }
    public record Detail(Proposal proposal, List<Comparison> comparisons, List<Candidate> explicitCandidates,
                         boolean candidatesTruncated) { }
    public record Candidate(String targetType, String targetId, String viaDesignItemId,
                            String assigneeMemberId, String origin) { }
    public record Revision(int revision, String snapshotHash, String submittedBy, String submittedAt,
                           String decision, String decisionReason, String decidedBy, String decidedAt) { }
    public record RevisionPage(List<Revision> items, boolean hasMore, Integer nextBeforeRevision) { }
    private ProposalDtos() { }
}
