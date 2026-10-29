package com.beez.backend.agenttoken;

import com.beez.backend.common.api.Ids;
import java.time.Instant;

/** 명세 00-common AgentToken. 평문·해시는 담지 않는다 (AGT-A02 처리 규칙). */
public record AgentTokenResponse(
        String id,
        String projectId,
        String label,
        Instant createdAt,
        Instant revokedAt,
        Instant lastUsedAt
) {
    public static AgentTokenResponse of(AgentToken t) {
        return new AgentTokenResponse(Ids.str(t.getId()), Ids.str(t.getProjectId()), t.getLabel(), t.getCreatedAt(),
                t.getRevokedAt(), t.getLastUsedAt());
    }
}
