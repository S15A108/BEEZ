package com.beez.backend.mcp.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * MCP-01 get_development_context 도구 인수 그대로 (02-mcp-tools MCP-01 요청 표). 어댑터는 이것만 넘긴다.
 * 패킷 내용 구성(승인 설계·의존 설계·미확정·관련 작업·근거)은 업무 서비스 안에서 한다 — 어댑터가 조율하지 않는다(-179 "입출력 변환만").
 *
 * @param request   개발 요청
 * @param projectId 토큰 프로젝트와 같은지만 본다 (선택)
 * @param taskId    관련 작업 (선택)
 */
public record GetDevelopmentContextArgs(@NotBlank String request, String projectId, String taskId) {
}
