package com.beez.backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** TSK-A06 담당자 변경 요청. ID 는 십진 문자열 */
public record ChangeAssigneeRequest(
        @NotBlank String assigneeMemberId,
        @NotNull Integer expectedLockVersion
) {
}
