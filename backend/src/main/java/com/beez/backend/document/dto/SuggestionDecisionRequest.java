package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

/**
 * DOC-A17. {@code replacementText} 는 APPLY 에서만 보낼 수 있고, 그 제안의 대화(DOC-A22)에서 비비가 낸 대체문과
 * 정확히 같을 때만 받는다. 생략하면 제안에 저장된 대체문을 쓴다.
 */
public record SuggestionDecisionRequest(@Positive(message = "조회한 작성 리비전은 양수여야 합니다.") long expectedRevision,
        @NotBlank(message = "보완 제안의 처리 방식을 선택해 주세요.")
        @Pattern(regexp = "APPLY|SKIP", message = "보완 제안은 반영하기 또는 넘기기를 선택해 주세요.") String decision,
        String replacementText) {
    public SuggestionDecisionRequest(long expectedRevision, String decision) { this(expectedRevision, decision, null); }
}
