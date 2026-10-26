package com.beez.backend.task.dto;

import jakarta.validation.constraints.NotNull;

/** TSK-A06 담당자 변경 요청 */
public record ChangeAssigneeRequest(
        @NotNull Long assigneeMemberId,
        @NotNull Integer expectedLockVersion
) {
}
