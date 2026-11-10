package com.beez.backend.agenttoken;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    /** /mcp 요청의 세션 확인. session_key 는 UNIQUE (V41) */
    Optional<AgentSession> findBySessionKey(String sessionKey);

    /**
     * 1인 1에이전트 (-178, Q06): 이 계정이 이 프로젝트에 가진 살아 있는 세션을 모두 끝낸다 — 토큰이 달라도(개발 PC·노트북) 같은 사람이다.
     * 호출하는 쪽이 멤버십 행을 잠근 뒤 부른다. 바뀐 행 수를 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update AgentSession s
               set s.endedAt = :now, s.endReason = 'REPLACED', s.updatedAt = :now
             where s.endedAt is null
               and s.agentTokenId in (select t.id from AgentToken t
                                       where t.projectId = :projectId and t.accountId = :accountId)
            """)
    int replaceLiveOfMember(@Param("projectId") long projectId, @Param("accountId") long accountId,
                            @Param("now") Instant now);

    /**
     * 새 연결이 같은 사람(이 계정·이 프로젝트)의 바로 이전 연결이 하던 작업을 이어 받는다 (-336). Claude Code 는 실행할 때마다 새로 연결하고,
     * 재시작한 에이전트는 이미 진행 중인 작업에 START 를 다시 보내지 않으므로, 넘기지 않으면 재시작 뒤 그 작업의 보고 끊김 판정이 다시
     * 작업 변경 시각만 본다. 이전 연결은 밀려났든(REPLACED) 닫혔든(CLOSED) 가장 최근 것 하나다. 그 작업이 아직 이 사람이 맡은
     * 진행 중·대기 작업일 때만 넘긴다 — 끝났거나 다른 사람에게 넘어간 작업은 가리키지 않는다. 바뀐 행 수(0 또는 1)를 돌려준다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update agent_session n
               set current_task_id = t.id
              from agent_session prev
              join agent_token k on k.id = prev.agent_token_id
              join task t on t.id = prev.current_task_id and t.project_id = k.project_id
              join project_member m on m.id = t.assignee_member_id and m.project_id = k.project_id and m.account_id = k.account_id
             where n.id = :id
               and t.status in ('IN_PROGRESS', 'WAITING')
               and prev.id = (select max(p.id)
                                from agent_session p
                                join agent_token pk on pk.id = p.agent_token_id
                               where pk.project_id = :projectId and pk.account_id = :accountId
                                 and p.id < :id)
            """, nativeQuery = true)
    int carryCurrentTask(@Param("id") long id, @Param("projectId") long projectId, @Param("accountId") long accountId);

    /** 클라이언트가 DELETE /mcp 로 닫음. 이미 끝난 세션(밀려남 등)은 처음 끝난 이유를 남긴다 */
    @Modifying
    @Query("""
            update AgentSession s
               set s.endedAt = :now, s.endReason = 'CLOSED', s.updatedAt = :now
             where s.id = :id
               and s.endedAt is null
            """)
    int close(@Param("id") long id, @Param("now") Instant now);

    /**
     * 마지막 통신 시각. 토큰 last_used_at 과 같이 기록이 staleBefore 보다 오래됐을 때만 바꾼다 — 매 요청 UPDATE 를 피한다.
     * 통신은 세션 수정이 아니라 updated_at 은 두지 않는다.
     */
    @Modifying
    @Query("""
            update AgentSession s
               set s.lastReportAt = :now
             where s.id = :id
               and s.endedAt is null
               and (s.lastReportAt is null or s.lastReportAt < :staleBefore)
            """)
    int touch(@Param("id") long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
