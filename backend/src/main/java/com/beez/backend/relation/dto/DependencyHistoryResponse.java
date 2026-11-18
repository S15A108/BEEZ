package com.beez.backend.relation.dto;

import java.time.Instant;

/** 명세 00-common DependencyHistory. 관계 한 쌍의 한 번 변경 (불변) */
public record DependencyHistoryResponse(String id, String fromDesignItemId, String toDesignItemId, String action,
                                        String origin, String status, String baselineId,
                                        String fromDesignItemVersionId, String toDesignItemVersionId,
                                        String changeProposalId, Long relationSetRevision, String reason,
                                        String actorAccountId, Instant createdAt) {
}
