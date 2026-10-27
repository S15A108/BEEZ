package com.beez.backend.designchange.dto;

import java.util.List;

/** 명세 00-common DesignChanges = MCP-03 get_design_changes 응답. 페이지 규칙은 공통 3절 */
public record DesignChangesResponse(
        BaselineSummaryResponse currentBaseline,
        List<PublishedChangeResponse> items,
        List<ValidationValidityResponse> validationValidity,
        boolean hasMore,
        String nextCursor
) {
}
