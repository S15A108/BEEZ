package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PublishInitialBaselineRequest(
        @NotBlank(message = "프로젝트 ID를 입력해 주세요.") String projectId,
        @NotEmpty(message = "기준 설계에 포함할 문서 버전 ID를 입력해 주세요.") List<@NotBlank(message = "문서 버전 ID는 비어 있을 수 없습니다.") String> documentVersionIds,
        @NotEmpty(message = "기준 설계에 포함할 항목 버전 ID를 입력해 주세요.") List<@NotBlank(message = "항목 버전 ID는 비어 있을 수 없습니다.") String> designItemVersionIds,
        @NotBlank(message = "검토한 전체 초안 스냅샷 해시를 입력해 주세요.") String draftSnapshotHash
) {
}
