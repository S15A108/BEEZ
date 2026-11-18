package com.beez.backend.project;

import jakarta.validation.constraints.NotBlank;

/** MEM-A08 요청. ID 는 십진 문자열 */
public record TransferLeadershipRequest(
        @NotBlank String targetMemberId
) {
}
