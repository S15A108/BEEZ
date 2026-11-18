package com.beez.backend.context.dto;

import java.time.Instant;
import java.util.List;

/** 명세 00-common ContextPacket (MCP-01 응답·MCP-06 PACKET·CTX-A01) */
public record ContextPacketResponse(
        String id,
        String projectId,
        String taskId,
        String baselineId,
        String request,
        List<DesignItemVersionResponse> designItems,
        List<String> constraints,
        List<String> unresolved,
        List<RelatedTaskDto> relatedTasks,
        List<SourceDto> sources,
        Instant createdAt,
        String contentHash
) {
}
