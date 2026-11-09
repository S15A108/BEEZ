package com.beez.backend.agenttoken;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AGT-A05 에이전트 연결 현황 목록 (S15P31A108-75). 읽기만 한다 — 세션 행은 MCP 연결(-178)이 적는다.
 * 세션 기록(AgentSessionService)은 MCP 요청 경로의 쓰기라, 화면용 읽기는 따로 둔다.
 */
@Service
public class AgentConnectionQueryService {

    private final ProjectAccessChecker access;
    private final JdbcClient jdbc;

    public AgentConnectionQueryService(ProjectAccessChecker access, JdbcClient jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    /**
     * 활성 멤버마다 가장 최근 세션 한 줄(폐기되지 않은 토큰의 것), 세션 번호 내림차순, 커서는 마지막 세션 번호.
     * 1인 1에이전트(-178)라 새 연결이 이전 것을 밀어내므로, 가장 최근 세션이 곧 지금 연결이거나 마지막 연결이다.
     * 연결 판정은 팀원 목록 agent_connected·MON-A05 와 같은 {@link AgentConnectionRule} 이다.
     * 한 번도 연결하지 않았거나 토큰을 모두 폐기한 멤버는 줄이 없다.
     */
    @Transactional(readOnly = true)
    public PageResponse<AgentConnectionResponse> list(CallerContext caller, long projectId, String cursor, Integer limit) {
        access.requireMember(caller, projectId);
        int size = PageResponse.normalizeLimit(limit);
        long beforeId = PageResponse.beforeId(cursor);
        List<Map<String, Object>> rows = jdbc.sql("""
                        with latest as (
                            select distinct on (k.account_id)
                                   s.id as session_id, k.account_id, s.last_report_at,
                                   %s as connected,
                                   %s as check_required
                              from agent_session s
                              join agent_token k on k.id = s.agent_token_id and k.project_id = :p and k.revoked_at is null
                             order by k.account_id, s.id desc
                        )
                        select l.session_id, m.id as member_id, l.last_report_at, l.connected, l.check_required
                          from latest l
                          join project_member m on m.project_id = :p and m.account_id = l.account_id and m.active = true
                         where l.session_id < :before
                         order by l.session_id desc
                         limit :limit
                        """.formatted(AgentConnectionRule.LIVE_SESSION_SQL, AgentConnectionRule.CHECK_REQUIRED_SQL))
                .param("p", projectId).param("before", beforeId).param("limit", size + 1)
                .query().listOfRows();
        return PageResponse.fromRows(rows, size, row -> ((Number) row.get("session_id")).longValue(),
                page -> page.stream().map(AgentConnectionQueryService::toResponse).toList());
    }

    private static AgentConnectionResponse toResponse(Map<String, Object> row) {
        // listOfRows 는 timestamptz 를 java.sql.Timestamp 로 준다 (타입을 지정해 읽을 때만 OffsetDateTime)
        Timestamp lastReport = (Timestamp) row.get("last_report_at");
        return new AgentConnectionResponse(
                Ids.str(((Number) row.get("session_id")).longValue()),
                Ids.str(((Number) row.get("member_id")).longValue()),
                Boolean.TRUE.equals(row.get("connected")),
                lastReport == null ? null : lastReport.toInstant(),
                Boolean.TRUE.equals(row.get("check_required")));
    }
}
