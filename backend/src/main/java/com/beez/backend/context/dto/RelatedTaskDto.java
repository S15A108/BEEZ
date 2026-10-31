package com.beez.backend.context.dto;

import jakarta.validation.constraints.NotBlank;

/** 명세 00-common RelatedTask. 패킷 구성(박상은)이 채운 값을 그대로 보존한다 */
public record RelatedTaskDto(@NotBlank String taskId, @NotBlank String planRevisionId, @NotBlank String status) {
}
