package com.beez.backend.status.dto;

import java.util.List;

/** MON-D01 응답. 최초 기준 발행 전에는 baseline 을 생략한다 (전역 non_null) */
public record DocumentStatusResponse(Baseline baseline, List<KindCount> byKind) {

    /** 명세 00-common BaselineSummary */
    public record Baseline(String id, int versionNo, String createdAt) {
    }

    /** 명세 00-common DocumentCount */
    public record KindCount(String kind, int draftCount, int approvedCount) {
    }
}
