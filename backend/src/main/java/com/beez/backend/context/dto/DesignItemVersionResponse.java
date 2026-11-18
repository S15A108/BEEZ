package com.beez.backend.context.dto;

import java.util.Map;

/** 명세 00-common DesignItemVersion (패킷의 승인 설계). source·content_hash(N)는 이번 범위에서 생략 */
public record DesignItemVersionResponse(
        String id,
        String designItemId,
        String key,
        String kind,
        Integer versionNo,
        String status,
        Integer schemaVersion,
        Map<String, Object> content
) {
}
