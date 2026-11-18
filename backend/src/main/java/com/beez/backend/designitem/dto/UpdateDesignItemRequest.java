package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpdateDesignItemRequest(
        @NotBlank(message = "프로젝트 ID를 입력해 주세요.") String projectId,
        @NotBlank(message = "조회한 항목 버전 ID를 입력해 주세요.") String expectedVersionId,
        @NotNull(message = "항목 본문을 입력해 주세요.") Map<String, Object> content,
        @NotBlank(message = "조회한 본문 해시를 입력해 주세요.") String expectedContentHash,
        Boolean replaceSource,
        String sourceDocumentVersionId,
        @jakarta.validation.constraints.Size(max = 10000, message = "출처 위치는 10,000자 이하여야 합니다.") String sourceLocation,
        String expectedDraftSnapshotHash
) {
    public UpdateDesignItemRequest(String projectId, String expectedVersionId, Map<String, Object> content, String expectedContentHash) {
        this(projectId, expectedVersionId, content, expectedContentHash, null, null, null, null);
    }
}
