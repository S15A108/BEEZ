package com.beez.backend.context.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 명세 00-common Source. 패킷 구성(박상은)이 채운 근거를 그대로 보존한다 */
public record SourceDto(@NotBlank String documentId, @NotBlank String documentVersionId, @NotNull Integer versionNo,
                        @NotBlank String location) {
}
