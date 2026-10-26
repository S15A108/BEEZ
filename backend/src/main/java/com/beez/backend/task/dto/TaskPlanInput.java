package com.beez.backend.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 계획 입력 (명세 00-common TaskPlanInput). 작업 생성(TSK-A01)과 리비전 추가(TSK-A04) 가 같은 모양을 쓴다.
 * JSON 은 snake_case 로 오가고(전역 설정) 자바 필드는 camelCase 다.
 */
public record TaskPlanInput(
        @NotBlank String title,
        @NotBlank String goal,
        @NotNull List<String> includedScope,
        @NotNull List<String> excludedScope,
        @NotBlank String approach,
        @NotNull List<String> completionCriteria,
        @NotNull List<Long> dependencyTaskIds,
        @NotNull @Valid List<DesignRefInput> designRefs,
        @NotNull List<Map<String, Object>> unlinked,
        @NotNull Boolean noContractChange,
        Long contextPacketId
) {
}
