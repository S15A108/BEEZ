package com.beez.backend.task.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * TSK-A04 요청 = 계획 입력 + 충돌 검사용 번호 두 개. 번호(revision_no·lock_version)는 ID 가 아니라 정수.
 * record 는 상속이 안 되어 TaskPlanInput 필드를 다시 적고 {@link #toPlanInput()} 으로 바꾼다.
 */
public record AddPlanRevisionRequest(
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
        String contextPacketId,
        @NotNull Integer expectedRevisionNo,
        @NotNull Integer expectedLockVersion
) {
    public TaskPlanInput toPlanInput() {
        return new TaskPlanInput(title, goal, includedScope, excludedScope, approach, completionCriteria,
                dependencyTaskIds, designRefs, unlinked, noContractChange, contextPacketId);
    }
}
