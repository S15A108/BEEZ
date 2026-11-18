package com.beez.backend.contextaccess.dto;

import com.beez.backend.common.api.Ids;
import com.beez.backend.contextaccess.entity.ContextAccessLog;
import java.time.Instant;

/** 명세 00-common ContextAccessLog (CTX-A06). ID 는 문자열, 없는 값은 생략 */
public record ContextAccessLogResponse(
        String id,
        String accountId,
        String agentTokenId,
        String taskId,
        String tool,
        String targetKind,
        String targetId,
        String baselineId,
        String packetId,
        Instant accessedAt
) {
    public static ContextAccessLogResponse from(ContextAccessLog log) {
        return new ContextAccessLogResponse(Ids.str(log.getId()), Ids.str(log.getAccountId()), Ids.str(log.getAgentTokenId()),
                Ids.str(log.getTaskId()), log.getTool(), log.getTargetKind(), Ids.str(log.getTargetId()),
                Ids.str(log.getBaselineId()), Ids.str(log.getPacketId()), log.getAccessedAt());
    }
}
