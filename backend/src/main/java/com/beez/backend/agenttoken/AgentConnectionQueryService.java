package com.beez.backend.agenttoken;

import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.api.PageResponse;
import com.beez.backend.common.auth.CallerContext;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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

    /** 확인 필요 임계값. 팀장 CANCEL 의 보고 끊김(TaskStateService)과 같은 설정을 읽어 두 판정이 어긋나지 않게 한다 */
    private final Duration checkRequiredAfter;

    public AgentConnectionQueryService(ProjectAccessChecker access, JdbcClient jdbc,
                                       @Value("${beez.task.stalled-after:PT30M}") Duration checkRequiredAfter) {
        this.access = access;
        this.jdbc = jdbc;
        this.checkRequiredAfter = checkRequiredAfter;
    }

    /**
     * 활성 멤버마다 가장 최근 세션 한 줄(폐기되지 않은 토큰의 것), 멤버 번호 내림차순, 커서는 마지막 멤버 번호.
     * 커서를 세션 번호로 두면 페이지를 넘기는 사이 재연결한 멤버(세션 번호가 커짐)가 어느 페이지에도 안 나온다 — 멤버 번호는 바뀌지 않는다.
     * 1인 1에이전트(-178)라 새 연결이 이전 것을 밀어내므로, 가장 최근 세션이 곧 지금 연결이거나 마지막 연결이다.
     * 연결 판정은 팀원 목록 agent_connected·MON-A05 와 같은 {@link AgentConnectionRule} 이다.
     * 확인 필요는 연결됨 + 그 멤버가 맡은 IN_PROGRESS 작업이 있음 + 임계값 넘게 통신 없음 — 팀장 CANCEL 의 보고 끊김
     * (TaskStateService.isStalled)과 같은 뜻이다. 할 일 없이 쉬는 에이전트는 요청을 보내지 않으므로 넣지 않는다.
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
                                   %s as connected
                              from agent_session s
                              join agent_token k on k.id = s.agent_token_id and k.project_id = :p and k.revoked_at is null
                             order by k.account_id, s.id desc
                        )
                        select l.session_id, m.id as member_id, l.last_report_at, l.connected,
                               (l.connected
                                and l.last_report_at <= now() - make_interval(secs => cast(:checkSecs as double precision))
                                and exists (select 1 from task t
                                             where t.project_id = :p
                                               and t.assignee_member_id = m.id
                                               and t.status = 'IN_PROGRESS')) as check_required
                          from latest l
                          join project_member m on m.project_id = :p and m.account_id = l.account_id and m.active = true
                         where m.id < :before
                         order by m.id desc
                         limit :limit
                        """.formatted(AgentConnectionRule.LIVE_SESSION_SQL))
                .param("p", projectId).param("checkSecs", checkRequiredAfter.toSeconds()).param("before", beforeId).param("limit", size + 1)
                .query().listOfRows();
        return PageResponse.fromRows(rows, size, row -> ((Number) row.get("member_id")).longValue(),
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
