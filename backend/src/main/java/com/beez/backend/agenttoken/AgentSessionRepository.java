package com.beez.backend.agenttoken;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {

    /** /mcp 요청의 세션 확인. session_key 는 UNIQUE (V42) */
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
