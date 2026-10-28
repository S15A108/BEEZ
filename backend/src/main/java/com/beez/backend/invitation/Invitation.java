package com.beez.backend.invitation;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 팀원 초대 (V2 invitation, V4 revoked_at). 링크의 토큰은 해시만 저장한다.
 * 상태는 컬럼으로 정해진다: 수락(accepted_at) · 폐기(revoked_at) · 만료(expires_at 지남) · 그 밖은 대기.
 */
@Entity
@Table(name = "invitation")
public class Invitation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id")
    private Long projectId;

    /** 지정한 경우만. 소문자로 저장한다 (계정 이메일과 같은 규칙) */
    private String email;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "invited_by")
    private Long invitedBy;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_account_id")
    private Long acceptedAccountId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Invitation() {
    }

    public static Invitation create(long projectId, String email, String tokenHash, long invitedBy, Instant expiresAt) {
        Invitation i = new Invitation();
        i.projectId = projectId;
        i.email = email;
        i.tokenHash = tokenHash;
        i.invitedBy = invitedBy;
        i.expiresAt = expiresAt;
        return i;
    }

    public void accept(long accountId, Instant now) {
        this.acceptedAt = now;
        this.acceptedAccountId = accountId;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getEmail() {
        return email;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
