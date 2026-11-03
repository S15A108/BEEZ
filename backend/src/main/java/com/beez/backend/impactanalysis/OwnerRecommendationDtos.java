package com.beez.backend.impactanalysis;

import java.util.List;

/** Candidate identities are project member IDs; notification delivery uses account IDs separately. */
public final class OwnerRecommendationDtos {
    private OwnerRecommendationDtos() {}

    public record Evidence(String type, String reason, String taskId, String taskKey, String planRevisionId,
                           String designItemId, String designItemVersionId, String referencedDesignItemVersionId, String documentId,
                           String documentVersionId, List<?> sources) {
        public Evidence { sources = List.copyOf(sources); }
    }

    public record Recommendation(String memberId, String area, List<Evidence> evidence) {
        public Recommendation { evidence = List.copyOf(evidence); }
    }

    /**
     * Direct candidates are a domain handoff, not an instruction to send a notification. An
     * incomplete or stale direct scan has an empty notificationCandidateMemberIds list.
     */
    public record DirectResult(int proposalRevision, String snapshotHash, String directReferenceStatus,
                               List<String> directReferenceReasons, String documentReferenceStatus,
                               List<String> documentReferenceReasons, List<Recommendation> recommendations,
                               List<String> notificationCandidateMemberIds) {
        public DirectResult {
            directReferenceReasons = List.copyOf(directReferenceReasons);
            documentReferenceReasons = List.copyOf(documentReferenceReasons);
            recommendations = List.copyOf(recommendations);
            notificationCandidateMemberIds = List.copyOf(notificationCandidateMemberIds);
        }
    }

    public record Result(int proposalRevision, String snapshotHash, String directReferenceStatus,
                         List<String> directReferenceReasons, String documentReferenceStatus,
                         List<String> documentReferenceReasons, String aiAnalysisStatus, boolean aiCurrent,
                         boolean aiReanalysisInProgress, int withheldAiCandidates,
                         List<Recommendation> recommendations, List<String> notificationCandidateMemberIds) {
        public Result {
            directReferenceReasons = List.copyOf(directReferenceReasons);
            documentReferenceReasons = List.copyOf(documentReferenceReasons);
            recommendations = List.copyOf(recommendations);
            notificationCandidateMemberIds = List.copyOf(notificationCandidateMemberIds);
        }
    }
}
