package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateDesignItemRequest(
        @NotBlank String kind,
        @NotNull @Positive Integer schemaVersion,
        @NotNull Map<String, Object> content,
        @Pattern(regexp = "[1-9][0-9]*") String sourceDocumentVersionId,
        @Size(max = 2000) String sourceLocation
) {
}
