package com.beez.backend.task.dto;

import com.beez.backend.task.entity.PlanRelation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * 계획이 참조하는 설계 항목 (명세 00-common DesignRef). ID 는 십진 문자열.
 * design_item_version_id 는 서버가 "현재 baseline 의 버전"과 같은지 확인한다. 다르면 409 version_conflict.
 */
public record DesignRefInput(
        @NotBlank String designItemId,
        @NotBlank String designItemVersionId,
        @NotNull PlanRelation relation,
        Map<String, Object> proposedContent
) {
}
