package com.beez.backend.designitem.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DesignItemResponse(
        String id,
        String designItemId,
        String key,
        String kind,
        int versionNo,
        String status,
        int schemaVersion,
        Map<String, Object> content,
        DesignItemSourceResponse source,
        String contentHash
) {
}
