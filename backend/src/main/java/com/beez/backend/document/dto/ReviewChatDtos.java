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

    public record DirectResponse(DocumentSuggestionResponse suggestion) { }
}
