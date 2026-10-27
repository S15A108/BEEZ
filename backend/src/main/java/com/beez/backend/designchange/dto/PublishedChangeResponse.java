package com.beez.backend.designchange.dto;

import java.util.List;

/**
 * 명세 00-common PublishedChange. 발행 한 건 = baseline 한 개.
 * proposal_id 는 명세상 필수지만 ERD 에서 첫 발행(v1)은 비어 있다. v1 은 "변경"이 아니라 시작점이라 목록에서 뺀다.
 * 그 밖의 발행에 제안이 없으면(사람 직접 발행) 생략한다 — 명세 확인 필요 사항으로 따로 적었다.
 */
public record PublishedChangeResponse(
        BaselineSummaryResponse baseline,
        String proposalId,
        List<ChangedItemResponse> changedItems,
        List<String> affectedTaskIds
) {
}
