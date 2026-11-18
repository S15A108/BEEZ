package com.beez.backend.changediscussion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** CHG-A12 요청. kind 가 없으면 COMMENT. DECISION 은 받지 않는다 (결정은 승인·반려 API 만 남긴다) */
public record CreateChangeCommentRequest(
        @NotBlank @Size(max = 4000) String body,
        String kind,
        @Size(max = 20) List<String> mentionedMemberIds
) {
}
