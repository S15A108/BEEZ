package com.beez.backend.designchange.dto;

import java.util.List;

/**
 * 명세 00-common DesignChanges = MCP-03 get_design_changes 응답. 페이지 규칙은 공통 3절.
 *
 * <p>impact_updates·result_has_more·next_result_cursor 는 get_design_changes 에만 싣는다 (S15P31A108-221).
 * report_task_state(MCP-12) 의 changes 에는 없다 — 값이 비어 응답에서 빠진다.
 *
 * @param impactUpdates    result_cursor 이후 정해진 발행 후 영향 분석 결과
 * @param resultHasMore    영향 결과가 더 있다. has_more(발행 변경)와 따로 본다
 * @param nextResultCursor 다음에 보낼 result_cursor. 받은 결과를 다 처리한 뒤 저장한다
 */
public record DesignChangesResponse(
        BaselineSummaryResponse currentBaseline,
        List<PublishedChangeResponse> items,
        List<ValidationValidityResponse> validationValidity,
        boolean hasMore,
        String nextCursor,
        List<ImpactUpdateResponse> impactUpdates,
        Boolean resultHasMore,
        String nextResultCursor
) {

    /** 같은 내용에 영향 결과를 붙인 것 (get_design_changes) */
    public DesignChangesResponse withImpactUpdates(ImpactUpdates updates) {
        return new DesignChangesResponse(currentBaseline, items, validationValidity, hasMore, nextCursor,
                updates.items(), updates.hasMore(), updates.nextCursor());
    }

    /** 영향 결과 한 페이지 */
    public record ImpactUpdates(List<ImpactUpdateResponse> items, boolean hasMore, String nextCursor) {
    }
}
