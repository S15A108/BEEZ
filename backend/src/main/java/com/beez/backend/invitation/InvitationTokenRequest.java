package com.beez.backend.invitation;

import jakarta.validation.constraints.NotBlank;

/** MEM-A04·A05 요청. 초대 링크의 토큰. */
public record InvitationTokenRequest(@NotBlank String token) {

    /** 메신저에서 복사한 링크의 앞뒤 공백·줄바꿈 때문에 유효한 초대가 404 가 되지 않게 지운다 (토큰은 base64url 이라 공백이 없다) */
    public InvitationTokenRequest {
        token = token == null ? null : token.strip();
    }
}
