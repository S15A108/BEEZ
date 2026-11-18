package com.beez.backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 명세 00-common CompletionCheck. 완료 기준 하나와 충족 여부 */
public record CompletionCheck(
        @NotBlank String criterion,
        @NotNull Boolean met
) {
}
