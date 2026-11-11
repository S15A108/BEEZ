package com.beez.backend.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** DOC-A22·A23 요청/응답. */
public final class ReviewChatDtos {
    private ReviewChatDtos() { }

    public record MessageRequest(@NotBlank(message = "비비에게 보낼 말을 입력해 주세요.")
            @Size(max = 5000, message = "비비에게 보낼 말은 5,000자 이하입니다.") String text) { }

    public record MessageResponse(DocumentMessageResponse message, DocumentMessageResponse reply) { }

    public record DirectRequest(@Positive(message = "조회한 작성 리비전은 양수여야 합니다.") long expectedRevision,
            @NotBlank(message = "요청 내용을 입력해 주세요.")
            @Size(max = 5000, message = "요청 내용은 5,000자 이하입니다.") String text) { }

    /**
     * outcome 은 CREATED(새 제안이 붙음) 또는 NO_CHANGE(비비가 고칠 곳이 없다고 답함, suggestion 은 null, reason 에 이유).
     * outcome 이 없는 예전 저장 응답은 CREATED 로 본다.
     */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record DirectResponse(String outcome, DocumentSuggestionResponse suggestion, String reason) {
        public static DirectResponse created(DocumentSuggestionResponse suggestion) {
            return new DirectResponse("CREATED", suggestion, null);
        }

        public static DirectResponse noChange(String reason) {
            return new DirectResponse("NO_CHANGE", null, reason);
        }

        public boolean isNoChange() {
            return "NO_CHANGE".equals(outcome);
        }
    }
}
