package com.beez.backend.agenttoken;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 에이전트 연결 세션 (V2 agent_session, V42 칸 추가, S15P31A108-178). MCP initialize 가 성공할 때마다 한 행이다.
 * 사용자 계정·토큰·MCP 세션 ID 는 서로 다른 식별자다 (02-mcp-tools "연결과 인증") — 이 행이 셋을 잇는다.
 * 끝남·마지막 통신은 동시에 바뀔 수 있어 엔티티를 고쳐 저장하지 않고 {@link AgentSessionRepository} 의 조건부 UPDATE 로 바꾼다.
 * {@code current_task_id} 는 아직 쓰지 않아 매핑하지 않는다.
 */
@Entity
@Table(name = "agent_session")
public class AgentSession extends BaseTimeEntity {

    /** 클라이언트가 보낸 clientInfo 를 저장할 때의 최대 길이. 표시용이라 넘으면 자른다 */
    static final int CLIENT_FIELD_MAX = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_token_id")
    private Long agentTokenId;

    /** MCP 세션 ID (Mcp-Session-Id) */
    @Column(name = "session_key")
    private String sessionKey;

    @Column(name = "client_name")
    private String clientName;

    @Column(name = "client_version")
    private String clientVersion;

    @Column(name = "last_report_at")
    private Instant lastReportAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason")
    private String endReason;

    protected AgentSession() {
    }

    static AgentSession open(long agentTokenId, String sessionKey, String clientName, String clientVersion,
                             Instant now) {
        AgentSession s = new AgentSession();
        s.agentTokenId = agentTokenId;
        s.sessionKey = sessionKey;
        s.clientName = clip(clientName);
        s.clientVersion = clip(clientVersion);
        s.lastReportAt = now;
        return s;
    }

    private static String clip(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        return v.length() > CLIENT_FIELD_MAX ? v.substring(0, CLIENT_FIELD_MAX) : v;
    }

    public Long getId() {
        return id;
    }

    public Long getAgentTokenId() {
        return agentTokenId;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public String getClientName() {
        return clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public Instant getLastReportAt() {
        return lastReportAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }
}
