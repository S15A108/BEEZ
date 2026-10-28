package com.beez.backend.common.access;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 에이전트 토큰이 어느 프로젝트 것이고 아직 쓸 수 있는지 읽는다.
 * {@link com.beez.backend.common.auth.CallerContext} 에는 토큰 ID 만 있어서, 권한 검사가 토큰의 프로젝트를 여기서 확인한다.
 * 토큰 엔티티는 -175 에서 만든다. 그 전까지 SQL 로 읽는다.
 */
@Component
public class AgentTokenScopes {

    /** 토큰의 프로젝트와 폐기 여부 */
    public record TokenScope(long projectId, boolean revoked) {
    }

    private final JdbcClient jdbc;

    public AgentTokenScopes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 이 계정이 발급한 토큰이면 그 범위. 남의 토큰이거나 없는 토큰이면 비어 있다. */
    public Optional<TokenScope> find(long agentTokenId, long accountId) {
        return jdbc.sql("""
                        select project_id, revoked_at is not null as revoked
                          from agent_token
                         where id = :tokenId
                           and account_id = :accountId
                        """)
                .param("tokenId", agentTokenId)
                .param("accountId", accountId)
                .query((rs, rowNum) -> new TokenScope(rs.getLong("project_id"), rs.getBoolean("revoked")))
                .optional();
    }
}
