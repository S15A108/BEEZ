package com.beez.backend.agenttoken;

/**
 * 에이전트 연결 판정 기준 (S15P31A108-75). 팀원 목록 {@code agent_connected}, 연결 현황 AGT-A05, 작업 현황 MON-A05 가
 * 같은 SQL 조각을 써서 화면마다 연결 여부가 갈리지 않게 한다. 조각은 세션 별칭 {@code s}(agent_session) 를 전제로 한다.
 *
 * <p>연결됨 = 끝나지 않았고 최근 2시간 안에 통신한 세션. Claude Code 는 {@code /exit} 때 DELETE /mcp 를 보내지 않아(11-09 실측)
 * {@code ended_at} 은 같은 사람의 새 연결에 밀려날 때(REPLACED)만 찍힌다. 그래서 끝남만 보면 꺼진 에이전트를 계속 연결로 그린다.
 * 2시간: MCP 서버가 마지막 요청 후 1~2시간에 유휴 세션을 지우므로(McpServerConfig sessionSweepInterval) 그보다 오래 조용하면
 * 서버에 세션이 남아 있지 않다.
 *
 * <p>확인 필요(AGT-A05 status_check_required) = 연결됨이지만 보고 끊김 임계값({@code beez.task.stalled-after}, 기본 30분)
 * 넘게 통신이 없음. 팀장 CANCEL 의 "보고가 끊긴 작업"(TaskStateService, AC-14)과 같은 설정값을 써서 두 판정이 어긋나지 않는다 —
 * 조각은 {@link AgentConnectionQueryService} 에 있다(설정값을 파라미터로 받아야 해서 상수가 아니다).
 * 통신이 없다고 끊겼다고 단정하지 않고, 통신이 있다고 실행 중으로 단정하지도 않는다 (AGT-A05 처리 규칙).
 *
 * <p>{@code last_report_at} 은 마지막 상태 보고가 아니라 마지막 <b>통신</b> 시각이다(요청마다 1분 단위, {@link AgentSessionRepository#touch}).
 */
public final class AgentConnectionRule {

    /** 살아 있는 연결: 끝나지 않았고 최근 2시간 안에 통신함 */
    public static final String LIVE_SESSION_SQL =
            "(s.ended_at is null and s.last_report_at > now() - interval '2 hours')";

    private AgentConnectionRule() {
    }
}
