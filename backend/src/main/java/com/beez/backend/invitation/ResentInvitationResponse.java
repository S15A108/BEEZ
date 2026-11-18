package com.beez.backend.invitation;

/** MEM-A11 응답. 갱신한 초대. 새 링크 토큰은 메일에만 담고 응답에는 넣지 않는다. */
public record ResentInvitationResponse(InvitationResponse invitation) {
}
