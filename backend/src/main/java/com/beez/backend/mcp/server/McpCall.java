package com.beez.backend.mcp.server;

import com.beez.backend.common.auth.CallerContext;

/**
 * 도구 호출 하나의 호출자 정보. 08 계약 1절 — 서비스에 {@code caller} 와 토큰의 {@code projectId} 를 넘긴다.
 * {@link CallerContext} 에는 프로젝트가 없어(사람 세션은 경로에서 정한다) 토큰 프로젝트를 함께 묶는다.
 *
 * @param caller    {@code CallerContext.agent(토큰 소유 계정, 토큰 ID, 이 요청의 연결 기록 ID)} — 활동의 agent_session_id 는 마지막 값이다 (-333)
 * @param projectId 토큰이 묶인 프로젝트. 도구 인수의 project_id 는 서비스가 이것과 같은지만 본다
 * @param sessionId MCP 세션 ID. 계정·토큰과 다른 식별자다 (02-mcp-tools "연결과 인증")
 */
public record McpCall(CallerContext caller, long projectId, String sessionId) {
}
