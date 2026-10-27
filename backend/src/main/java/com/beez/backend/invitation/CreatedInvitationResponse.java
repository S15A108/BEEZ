package com.beez.backend.invitation;

/** MEM-A01 응답. invite_url 에만 평문 토큰이 들어가고, 다시 조회할 방법은 없다. */
public record CreatedInvitationResponse(InvitationResponse invitation, String inviteUrl) {
}
