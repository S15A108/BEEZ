package com.beez.backend.status.dto;

import java.util.List;

/** MON-C01 응답 */
public record ProposalStatusResponse(List<StateCount> byStatus, int reviewRequiredCount) {

    /** 명세 00-common StateCount */
    public record StateCount(String status, int count) {
    }
}
