package com.beez.backend.invitation;

import jakarta.validation.constraints.NotBlank;

/** MEM-A04·A05 요청. 초대 링크의 토큰. */
public record InvitationTokenRequest(@NotBlank String token) {
}
