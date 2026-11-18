package com.beez.backend.project;

import com.beez.backend.common.api.Ids;
import com.beez.backend.designchange.dto.BaselineSummaryResponse;
import java.time.Instant;

/**
 * 명세 00-common Project. my_role 은 조회자의 역할, current_baseline 은 최초 발행 전이면 응답에서 빠진다(전역 non_null).
 */
public record ProjectResponse(
        String id,
        String name,
        String description,
        ProjectRole myRole,
        BaselineSummaryResponse currentBaseline,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectResponse of(Project p, ProjectRole myRole, BaselineSummaryResponse currentBaseline) {
        return new ProjectResponse(Ids.str(p.getId()), p.getName(), p.getDescription(), myRole, currentBaseline,
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
