package com.beez.backend.context.dto;

import jakarta.validation.constraints.NotBlank;

/** 명세 00-common VersionRef. ID 는 문자열 */
public record VersionRefDto(@NotBlank String designItemId, @NotBlank String designItemVersionId) {
}
