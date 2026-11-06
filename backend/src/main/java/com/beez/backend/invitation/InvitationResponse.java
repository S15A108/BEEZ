package com.beez.backend.invitation;

import com.beez.backend.common.api.Ids;
import java.time.Instant;

/**
 * 명세 00-common Invitation. 토큰은 담지 않는다 (MEM-A01 처리 규칙).
 * 초대는 만료가 없어 expires_at 은 내보내지 않는다 (Q05 6, -220).
 */
public record InvitationResponse(
        String id,
        String projectId,
        String invitedEmail,
        Instant acceptedAt,
        Instant revokedAt
) {
    public static InvitationResponse of(Invitation i) {
        return new InvitationResponse(Ids.str(i.getId()), Ids.str(i.getProjectId()), i.getEmail(),
                i.getAcceptedAt(), i.getRevokedAt());
    }
}
