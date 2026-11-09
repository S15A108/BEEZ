package com.beez.backend.agenttoken;

import com.beez.backend.common.access.AgentPrincipal;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.access.ProjectActor;
import com.beez.backend.common.api.Ids;
import com.beez.backend.common.auth.CallerContext;
import com.beez.backend.project.ProjectMember;
import com.beez.backend.project.ProjectMemberRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 에이전트 연결 세션 (S15P31A108-178). /mcp 앞의 {@code McpSessionFilter} 가 연결·요청·해제 때 부르고,
 * MCP-15 {@code check_connection} 이 연결 확인을 읽는다.
 * <ul>
 *   <li>식별: initialize 가 성공하면 MCP 세션 ID·토큰·클라이언트 이름을 한 행으로 남긴다</li>
 *   <li>1인 1에이전트(Q06): 한 계정이 한 프로젝트에 가진 살아 있는 세션은 하나다. 나중 연결이 이기고 이전 세션은 REPLACED 로 끝난다.
 *       먼저 연결을 지키면, DELETE 없이 꺼진 클라이언트(Claude Code 는 정상 종료도 DELETE 를 보내지 않는다, 11-09 실측)가 정리될 때까지 1~2시간 다시 붙지 못한다</li>
 *   <li>결속: 세션은 만든 토큰에만 쓸 수 있다. 다른 토큰이 남의 세션 ID 를 보내거나 기록이 없는 세션이면 없는 세션처럼 거부한다
 *       (-176 PR #103 메모)</li>
 *   <li>마지막 통신: 요청마다 last_report_at 을 1분 단위로 갱신한다. 연결 현황 조회는 -75</li>
 * </ul>
 * 토큰 폐기·멤버십 종료는 이 표가 아니라 토큰 검증({@link StoredAgentTokenVerifier})이 매 요청 401 로 막는다.
 */
@Service
public class AgentSessionService {

    /** last_report_at 갱신 간격. 토큰 last_used_at 과 같다 */
    static final Duration TOUCH_INTERVAL = StoredAgentTokenVerifier.LAST_USED_INTERVAL;

    private static final Logger log = LoggerFactory.getLogger("com.beez.mcp");

    /** 세션 ID 가 붙은 /mcp 요청을 받아도 되는지 */
    public enum Admission {
        /** 이 토큰이 연 살아 있는 세션 */
        OK,
        /**
         * 없는 세션으로 404 — 기록이 없거나(기록이 실패한 연결 포함), 다른 토큰이 열었거나, 클라이언트가 이미 닫았다.
         * 기록이 없는 세션을 통과시키면 결속이 기록 성공에 기대게 되므로 막는다(fail-closed)
         */
        NOT_FOUND,
        /** 같은 계정의 새 연결에 밀려난 세션. 409 agent_session_replaced */
        REPLACED
    }

    private final AgentSessionRepository sessions;
    private final AgentTokenRepository tokens;
    private final ProjectMemberRepository members;
    private final ProjectAccessChecker access;

    public AgentSessionService(AgentSessionRepository sessions, AgentTokenRepository tokens,
                               ProjectMemberRepository members, ProjectAccessChecker access) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.members = members;
        this.access = access;
    }

    /**
     * initialize 성공 직후. 멤버십 행을 잠가 같은 사람의 동시 연결이 둘 다 살아남지 않게 한다 — 토큰 발급(AGT-A01)·팀원 제외가 잠그는 행과 같다.
     * 그사이 멤버십이 끝났으면 기록하지 않는다(다음 요청이 토큰 검증에서 401 이다).
     */
    @Transactional
    public void connect(AgentPrincipal principal, String sessionKey, String clientName, String clientVersion) {
        Optional<ProjectMember> member = members.findActiveMembership(principal.projectId(), principal.accountId());
        if (member.isEmpty()) {
            return;
        }
        // 잠근 뒤 활성 여부는 쿼리로 다시 본다. 위에서 읽은 엔티티는 잠금을 기다리는 사이 커밋된 제외·나가기를 모른다
        // (AgentTokenService.issue 와 같은 함정)
        members.findForUpdate(member.get().getId());
        if (!members.isActiveMember(member.get().getId(), principal.projectId())) {
            return;
        }
        Instant now = Instant.now();
        int replaced = sessions.replaceLiveOfMember(principal.projectId(), principal.accountId(), now);
        AgentSession saved = sessions.save(
                AgentSession.open(principal.agentTokenId(), sessionKey, clientName, clientVersion, now));
        log.info("mcp session connected agent_session={} project={} account={} token={} client={} replaced={}",
                saved.getId(), principal.projectId(), principal.accountId(), principal.agentTokenId(),
                saved.getClientName(), replaced);
    }

    /** 세션 ID 가 붙은 요청마다. 받아도 되면 마지막 통신 시각도 남긴다 */
    @Transactional
    public Admission admit(AgentPrincipal principal, String sessionKey) {
        Optional<AgentSession> found = sessions.findBySessionKey(sessionKey);
        if (found.isEmpty()) {
            return Admission.NOT_FOUND;
        }
        AgentSession s = found.get();
        if (s.getAgentTokenId() != principal.agentTokenId()) {
            log.info("mcp session rejected reason=foreign_token agent_session={} token={}", s.getId(),
                    principal.agentTokenId());
            return Admission.NOT_FOUND;
        }
        if (s.getEndedAt() != null) {
            return "REPLACED".equals(s.getEndReason()) ? Admission.REPLACED : Admission.NOT_FOUND;
        }
        Instant now = Instant.now();
        sessions.touch(s.getId(), now, now.minus(TOUCH_INTERVAL));
        return Admission.OK;
    }

    /** DELETE /mcp 가 성공한 뒤. 이 토큰의 세션만 닫는다 */
    @Transactional
    public void close(AgentPrincipal principal, String sessionKey) {
        sessions.findBySessionKey(sessionKey)
                .filter(s -> s.getAgentTokenId() == principal.agentTokenId())
                .ifPresent(s -> sessions.close(s.getId(), Instant.now()));
    }

    /**
     * MCP-15 check_connection. 토큰 검증을 지나 여기까지 왔으면 연결된 것이다. 서비스 공통 권한 검사도 똑같이 거친다
     * (다른 도구가 성공할 조건과 같아야 시험이 뜻이 있다).
     */
    @Transactional(readOnly = true)
    public ConnectionCheckResult check(CallerContext caller, long projectId, String sessionKey) {
        ProjectActor actor = access.requireMember(caller, projectId);
        AgentToken token = tokens.findById(actor.agentTokenId()).orElseThrow();
        AgentSession session = sessionKey == null ? null : sessions.findBySessionKey(sessionKey)
                .filter(s -> s.getAgentTokenId().equals(token.getId()))
                .orElse(null);
        return new ConnectionCheckResult(
                true,
                Ids.str(projectId),
                Ids.str(actor.accountId()),
                Ids.str(actor.memberId()),
                Ids.str(token.getId()),
                token.getLabel(),
                sessionKey,
                session == null ? null : Ids.str(session.getId()),
                session == null ? null : session.getClientName(),
                session == null ? null : session.getClientVersion(),
                session == null ? null : session.getCreatedAt());
    }

    /**
     * check_connection 응답 (02-mcp-tools MCP-15).
     *
     * @param sessionId      MCP 세션 ID (Mcp-Session-Id)
     * @param agentSessionId 연결 기록 ID — 연결 현황(AGT-A06)·활동의 agent_session_id. 기록이 없으면 생략
     */
    public record ConnectionCheckResult(boolean connected, String projectId, String accountId, String memberId,
                                        String agentTokenId, String tokenLabel, String sessionId,
                                        String agentSessionId, String clientName, String clientVersion,
                                        Instant connectedAt) {
    }
}
