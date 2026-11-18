package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DocumentDecisionRequest(@NotBlank(message = "문서 검토 결과를 선택해 주세요.")
        @Pattern(regexp = "APPROVE|REJECT", message = "문서 검토 결과는 승인 또는 반려를 선택해 주세요.") String decision,
        @Size(max = 10000, message = "반려 사유는 10,000자 이하여야 합니다.") String rejectionReason) { }
