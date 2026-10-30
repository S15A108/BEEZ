package com.beez.backend.validation.dto;

import java.util.List;
import java.util.Map;

/** 명세 00-common Finding. sources 는 명세 Source(document_id·document_version_id·version_no·location) 목록 */
public record FindingResponse(String id, String validationRunId, String source, String kind, String severity, String message,
                              String designItemId, String designItemVersionId, String relatedTaskId,
                              List<Map<String, Object>> sources, String resolution, String nextAction) {
}
