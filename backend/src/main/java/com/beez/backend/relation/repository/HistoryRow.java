package com.beez.backend.relation.repository;

import java.time.Instant;

/** design_item_dependency_history 한 줄. 저장 전에는 id·createdAt 이 null */
public record HistoryRow(Long id, Long projectId, Long fromItemId, Long toItemId, String action, String originAfter,
                         String statusAfter, Long baselineId, Long fromVersionId, Long toVersionId,
                         Long changeProposalId, Long relationSetRevision, String reason, Long actorAccountId,
                         Instant createdAt) {

    /** 리비전을 정한 뒤 저장할 값으로 바꾼다 */
    public HistoryRow withRevision(Long revision) {
        return new HistoryRow(id, projectId, fromItemId, toItemId, action, originAfter, statusAfter, baselineId,
                fromVersionId, toVersionId, changeProposalId, revision, reason, actorAccountId, createdAt);
    }
}
