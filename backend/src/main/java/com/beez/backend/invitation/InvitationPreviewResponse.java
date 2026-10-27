package com.beez.backend.invitation;

import java.time.Instant;

/** MEM-A04 응답. 로그인 전 화면에 필요한 최소 정보만. */
public record InvitationPreviewResponse(String projectName, Instant expiresAt, boolean loginRequired) {
}
