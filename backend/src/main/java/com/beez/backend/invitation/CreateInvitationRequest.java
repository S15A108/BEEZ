package com.beez.backend.invitation;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** MEM-A01 요청. email 은 선택이고, 빈 문자열은 "지정 안 함"으로 본다. */
public record CreateInvitationRequest(@Email @Size(max = 254) String email) {

    /** @Email 검증 전에 앞뒤 공백을 지운다 (회원가입 RegisterRequest 와 같은 이유) */
    public CreateInvitationRequest {
        email = email == null || email.isBlank() ? null : email.trim();
    }
}
