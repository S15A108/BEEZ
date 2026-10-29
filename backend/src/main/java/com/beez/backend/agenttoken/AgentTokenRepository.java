package com.beez.backend.agenttoken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentTokenRepository extends JpaRepository<AgentToken, Long> {

    /** /mcp Bearer 검증. token_hash 가 UNIQUE 라 바로 찾는다. */
    Optional<AgentToken> findByTokenHash(String tokenHash);

    /** 이 계정이 발급한 토큰. 남의 토큰은 없는 것으로 본다 (권한 검사, 폐기). */
    Optional<AgentToken> findByIdAndAccountId(long id, long accountId);

    /** 발급 한도(Q06) — 프로젝트에서 이 계정의 폐기 안 된 토큰 수 */
    long countByProjectIdAndAccountIdAndRevokedAtIsNull(long projectId, long accountId);

    /** 내 토큰 목록 (AGT-A02). 폐기된 토큰도 보인다. id 내림차순 커서 — 프로젝트·초대 목록과 같은 방식. */
    @Query("""
            select t from AgentToken t
             where t.accountId = :accountId
               and t.id < :beforeId
             order by t.id desc
            """)
    List<AgentToken> findMine(@Param("accountId") long accountId, @Param("beforeId") long beforeId, Pageable pageable);

    /** {@link #findMine} 에 프로젝트 필터. 없는 프로젝트·남의 프로젝트도 오류 없이 0건이다 (-172 합의 3). */
    @Query("""
            select t from AgentToken t
             where t.accountId = :accountId
               and t.projectId = :projectId
               and t.id < :beforeId
             order by t.id desc
            """)
    List<AgentToken> findMineInProject(@Param("accountId") long accountId, @Param("projectId") long projectId,
                                       @Param("beforeId") long beforeId, Pageable pageable);

    /**
     * 본인 토큰 폐기 (AGT-A03). 이미 폐기된 토큰은 건드리지 않아 처음 폐기 시각이 남는다. 바뀐 행 수를 돌려준다.
     * 조건부 UPDATE 한 문장이라 사용 기록 갱신과 겹쳐도 서로 덮어쓰지 않는다.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update AgentToken t
               set t.revokedAt = :now, t.updatedAt = :now
             where t.id = :id
               and t.accountId = :accountId
               and t.revokedAt is null
            """)
    int revoke(@Param("id") long id, @Param("accountId") long accountId, @Param("now") Instant now);

    /** 프로젝트 삭제와 같은 트랜잭션에서 그 프로젝트의 토큰을 모두 폐기한다 (-172 합의 4, PRJ-A05). */
    @Modifying(flushAutomatically = true)
    @Query("""
            update AgentToken t
               set t.revokedAt = :now, t.updatedAt = :now
             where t.projectId = :projectId
               and t.revokedAt is null
            """)
    int revokeAllInProject(@Param("projectId") long projectId, @Param("now") Instant now);

    /**
     * 마지막 사용 시각. 매 MCP 호출마다 쓰지 않도록 기록이 staleBefore 보다 오래됐을 때만 바꾼다.
     * 사용은 토큰 수정이 아니라 updated_at 은 두지 않는다.
     */
    @Modifying
    @Query("""
            update AgentToken t
               set t.lastUsedAt = :now
             where t.id = :id
               and (t.lastUsedAt is null or t.lastUsedAt < :staleBefore)
            """)
    int touchLastUsed(@Param("id") long id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);
}
