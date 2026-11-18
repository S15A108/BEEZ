package com.beez.backend.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 계획 입력 (명세 00-common TaskPlanInput). 작업 생성(TSK-A01)과 리비전 추가(TSK-A04)가 같은 모양을 쓰고,
 * 리비전 조회 응답(TaskPlanRevision.plan)에도 그대로 쓴다. ID 는 십진 문자열 (00-common 3절).
 */
public record TaskPlanInput(
        @NotBlank String title,
        @NotBlank String goal,
        @NotNull List<@NotNull String> includedScope,
        @NotNull List<@NotNull String> excludedScope,
        @NotBlank String approach,
        @NotNull List<@NotNull String> completionCriteria,
        @NotNull List<@NotBlank String> dependencyTaskIds,
        @NotNull List<@NotNull @Valid DesignRefInput> designRefs,
        @NotNull List<@NotNull Map<String, Object>> unlinked,
        @NotNull Boolean noContractChange,
        String contextPacketId
) {
}
