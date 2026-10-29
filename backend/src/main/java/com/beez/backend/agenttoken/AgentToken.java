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
 * 프로젝트별 에이전트 토큰 (V2 agent_token, V7 name NULL 허용). 평문은 발급 응답에서 한 번만 주고 해시만 저장한다 (NFR-03).
 * 폐기·마지막 사용 시각은 동시에 바뀔 수 있어 엔티티를 고쳐 저장하지 않고 {@link AgentTokenRepository} 의 조건부 UPDATE 로 바꾼다.
 */
@Entity
@Table(name = "agent_token")
public class AgentToken extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    @Column(name = "account_id")
    private Long accountId;

    /** API 필드 label. DB 컬럼 이름은 V2 의 name 그대로 */
    @Column(name = "name")
    private String label;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected AgentToken() {
    }

    public static AgentToken issue(long projectId, long accountId, String label, String tokenHash) {
        AgentToken t = new AgentToken();
        t.projectId = projectId;
        t.accountId = accountId;
        t.label = label;
        t.tokenHash = tokenHash;
        return t;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getLabel() {
        return label;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
