package com.beez.backend.task.repository;

import com.beez.backend.agenttoken.AgentConnectionRule;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 작업·에이전트 현황 읽기 (S15P31A108-64). 검증 상태 쪽 읽기는 ValidationStatusLookup 에 있다 */
@Repository
public class TaskStatusRepository {

    private final JdbcClient jdbc;

    public TaskStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 작업 상태별 건수. key = status */
    public Map<String, Integer> taskCountsByStatus(Long projectId) {
        return counts("select status as key, count(*) as cnt from task where project_id = :p group by status", projectId);
    }

    /** WAITING 작업의 대기 이유별 건수. key = waiting_reason */
    public Map<String, Integer> waitingCountsByReason(Long projectId) {
        return counts("""
                select waiting_reason as key, count(*) as cnt from task
                 where project_id = :p and status = 'WAITING' and waiting_reason is not null
                 group by waiting_reason
                """, projectId);
    }

    /**
     * 에이전트 세션 한 페이지 (세션 번호 내림차순, beforeId 미만, limit 개).
     * 폐기되지 않은 토큰 + 아직 활성인 멤버의 살아 있는 세션만. 살아 있음은 팀원 목록 agent_connected·연결 현황 AGT-A05 와 같은
     * {@link AgentConnectionRule#LIVE_SESSION_SQL}(끝나지 않았고 최근 2시간 안에 통신, -75)이다 — Claude Code 는 종료 때 DELETE 를 보내지 않아
     * 그냥 끝낸 세션은 ended_at 이 비어 남으므로, ended_at 만 보면 꺼진 에이전트를 계속 보여 준다. 세션이 보고한 현재 작업이 이 프로젝트 것이면 작업 칸을 채운다.
     * 행: session_id, member_id, agent_name, task_id, task_title, task_status, waiting_reason, current_plan_revision_id
     */
    public List<Map<String, Object>> agentSessions(Long projectId, Long memberId, long beforeId, int limit) {
        return jdbc.sql("""
                        select s.id as session_id, m.id as member_id, k.name as agent_name,
                               t.id as task_id, t.title as task_title, t.status as task_status, t.waiting_reason,
                               t.current_plan_revision_id
                          from agent_session s
                          join agent_token k on k.id = s.agent_token_id and k.project_id = :p and k.revoked_at is null
                          join project_member m on m.project_id = k.project_id and m.account_id = k.account_id and m.active = true
                          left join task t on t.id = s.current_task_id and t.project_id = :p
                         where s.id < :before
                           and %s
                           and (:member is null or m.id = :member)
                         order by s.id desc
                         limit :limit
                        """.formatted(AgentConnectionRule.LIVE_SESSION_SQL))
                .param("p", projectId).param("before", beforeId).param("member", memberId, Types.BIGINT).param("limit", limit)
                .query().listOfRows();
    }

    private Map<String, Integer> counts(String sql, Long projectId) {
        Map<String, Integer> out = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql(sql).param("p", projectId).query().listOfRows()) {
            out.put((String) row.get("key"), ((Number) row.get("cnt")).intValue());
        }
        return out;
    }
}
