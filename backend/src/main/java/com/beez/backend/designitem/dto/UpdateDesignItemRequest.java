package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpdateDesignItemRequest(
        @NotBlank String projectId,
        @NotBlank String expectedVersionId,
        @NotNull Map<String, Object> content,
        @NotBlank String expectedContentHash
) {
}
