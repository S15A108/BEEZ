package com.beez.backend.context.dto;

import java.util.List;

/** MCP-05 find_context 인수. query 필수, 나머지 선택. kinds 는 DECISION|PROGRESS|ISSUE|HANDOVER */
public record FindContextArgs(String query, String taskId, List<String> kinds, String cursor, Integer limit) {
}
