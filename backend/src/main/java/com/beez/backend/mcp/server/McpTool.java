package com.beez.backend.mcp.server;

/**
 * MCP 도구 하나. {@code @Component} 로 등록하면 {@link McpServerConfig} 가 모아 tools/list 에 싣는다.
 * 추가 방법은 docs/design/mcp-server-S15P31A108-176.md 4절.
 *
 * <p>공통 처리(호출자·프로젝트 결정, snake_case 인수 변환, 오류 결과, 요청 로그)는 {@link McpToolHandler} 가 한다.
 * 구현은 08 계약대로 <b>인수를 담당 서비스에 넘기고 결과를 돌려주는 일만</b> 한다 — 트랜잭션을 열지 않고 업무 규칙을 복제하지 않는다.
 *
 * @param <A> 도구 인수 레코드. JSON 의 snake_case 키가 camelCase 칸으로 들어온다 (예: task_id → taskId)
 */
public interface McpTool<A> {

    /** 도구 이름 (02-mcp-tools 의 이름 그대로, 예: get_development_context). 서버 안에서 유일해야 한다 */
    String name();

    /** 에이전트가 읽는 설명. 언제 부르는지와 주의할 점을 적는다 */
    String description();

    /**
     * 입력 JSON Schema (type=object). 키는 snake_case. 에이전트가 인수를 만드는 안내용이다 —
     * 서버는 이 스키마로 거르지 않고, 인수 레코드 변환 실패와 서비스의 검증 표시로 400 validation_failed 를 낸다.
     */
    String inputSchema();

    Class<A> argsType();

    /**
     * @return 도구 성공 결과의 업무 payload. 레코드·Map 처럼 JSON 객체가 되는 값을 돌려준다 (snake_case 로 직렬화)
     * @throws com.beez.backend.common.api.ApiException 업무 오류. code·message 가 도구 실패 결과로 나간다
     */
    Object call(McpCall call, A args);
}
