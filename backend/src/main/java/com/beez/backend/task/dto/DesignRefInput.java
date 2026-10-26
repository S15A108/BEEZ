package com.beez.backend.task.dto;

import com.beez.backend.task.entity.PlanRelation;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 계획이 참조하는 설계 항목 (명세 00-common DesignRef).
 * design_item_version_id 는 클라이언트가 보내되, 서버가 "현재 baseline 의 버전"과 같은지 확인한다. 다르면 409 version_conflict.
 */
public record DesignRefInput(
        @NotNull Long designItemId,
        @NotNull Long designItemVersionId,
        @NotNull PlanRelation relation,
        Map<String, Object> proposedContent
) {
}
