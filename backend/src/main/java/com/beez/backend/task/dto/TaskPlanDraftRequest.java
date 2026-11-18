package com.beez.backend.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 작업 계획 문서 초안 요청 (S15P31A108-385). 제목만 있어도 된다. 작업은 아직 만들어지기 전일 수 있다. */
public record TaskPlanDraftRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String memo
) {
}
