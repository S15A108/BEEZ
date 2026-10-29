package com.beez.backend.agenttoken;

import com.beez.backend.auth.agent.AgentTokenException;
import com.beez.backend.auth.agent.AgentTokenVerifier;
import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.project.ProjectMemberRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * /mcp Bearer 토큰 검증 (S15P31A108-175, -178 MCP 인증이 쓴다). 매 요청 DB 를 읽는다 — 캐시가 없어 폐기·제외 커밋 직후 요청부터 막힌다.
 * <ol>
 *   <li>해시가 없는 토큰 → 401 {@code unauthenticated}</li>
 *   <li>폐기된 토큰 → 401 {@code token_revoked}</li>
 *   <li>멤버십이 끝남(제외·나가기)·프로젝트 삭제·계정 탈퇴 → 401 {@code token_revoked} (-172 참고 2 "제외 직후 토큰")</li>
 * </ol>
 * 통과하면 last_used_at 을 남긴다. 서비스의 {@code ProjectAccessChecker.requireMember} 도 토큰 행을 다시 읽어 마지막으로 막는다.
 * 평문·해시는 로그에 남기지 않는다.
 */
@Component
public class StoredAgentTokenVerifier implements AgentTokenVerifier {

    /** last_used_at 갱신 간격. 매 호출 UPDATE 를 피하려는 값이라 표시용 "마지막 사용"은 이만큼 늦을 수 있다. */
    static final Duration LAST_USED_INTERVAL = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger("com.beez.access");

    private final AgentTokenRepository tokens;
    private final ProjectMemberRepository members;

    public StoredAgentTokenVerifier(AgentTokenRepository tokens, ProjectMemberRepository members) {
        this.tokens = tokens;
        this.members = members;
    }

    @Override
    @Transactional
    public AgentPrincipal verify(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new AgentTokenException("unauthenticated", "empty agent token");
        }
        AgentToken token = tokens.findByTokenHash(SecretTokens.hash(rawToken))
                .orElseThrow(() -> new AgentTokenException("unauthenticated", "unknown agent token"));
        if (token.isRevoked()) {
            throw revoked("token_revoked token=" + token.getId());
        }
        if (members.findActiveMembership(token.getProjectId(), token.getAccountId()).isEmpty()) {
            throw revoked("token_membership_inactive token=" + token.getId() + " project=" + token.getProjectId()
                    + " account=" + token.getAccountId());
        }
        Instant now = Instant.now();
        tokens.touchLastUsed(token.getId(), now, now.minus(LAST_USED_INTERVAL));
        return new AgentPrincipal(token.getAccountId(), token.getProjectId(), token.getId());
    }

    private static AgentTokenException revoked(String reason) {
        log.info("access denied status=401 reason={}", reason);
        return new AgentTokenException("token_revoked", reason);
    }
}
