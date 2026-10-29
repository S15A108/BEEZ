package com.beez.backend.common.access;

import com.beez.backend.agenttoken.AgentTokenRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 에이전트 토큰이 어느 프로젝트 것이고 아직 쓸 수 있는지 읽는다.
 * {@link com.beez.backend.common.auth.CallerContext} 에는 토큰 ID 만 있어서, 권한 검사가 토큰의 프로젝트를 여기서 확인한다.
 */
@Component
public class AgentTokenScopes {

    /** 토큰의 프로젝트와 폐기 여부 */
    public record TokenScope(long projectId, boolean revoked) {
    }

    private final AgentTokenRepository tokens;

    public AgentTokenScopes(AgentTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** 이 계정이 발급한 토큰이면 그 범위. 남의 토큰이거나 없는 토큰이면 비어 있다. */
    public Optional<TokenScope> find(long agentTokenId, long accountId) {
        return tokens.findByIdAndAccountId(agentTokenId, accountId)
                .map(t -> new TokenScope(t.getProjectId(), t.isRevoked()));
    }
}
