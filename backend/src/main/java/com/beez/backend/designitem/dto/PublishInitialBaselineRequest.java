package com.beez.backend.designitem.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PublishInitialBaselineRequest(
        @NotBlank String projectId,
        @NotEmpty List<@NotBlank String> documentVersionIds,
        @NotEmpty List<@NotBlank String> designItemVersionIds,
        @NotBlank String draftSnapshotHash
) {
}
