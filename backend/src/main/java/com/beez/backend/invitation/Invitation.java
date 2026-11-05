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
 * 팀원 초대 (V2 invitation, V4 revoked_at, V33 만료 제거). 링크의 토큰은 해시만 저장한다.
 * 상태는 컬럼으로 정해진다: 수락(accepted_at) · 폐기(revoked_at) · 그 밖은 대기(PENDING). 만료는 없다 (Q05 6).
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

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_account_id")
    private Long acceptedAccountId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Invitation() {
    }

    public static Invitation create(long projectId, String email, String tokenHash, long invitedBy) {
        Invitation i = new Invitation();
        i.projectId = projectId;
        i.email = email;
        i.tokenHash = tokenHash;
        i.invitedBy = invitedBy;
        return i;
    }

    /** 초대 다시 보내기 (MEM-A11). 토큰을 바꾸면 이전 링크는 바로 무효다 — 해시가 하나뿐이라 옛 토큰으로는 찾을 수 없다. */
    public void rotateToken(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public void accept(long accountId, Instant now) {
        this.acceptedAt = now;
        this.acceptedAccountId = accountId;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }

    /** 초대받은 사람이 탈퇴했다 (ACC-A10, -277). 주소만 지우고 초대 기록(수락·폐기 시각)은 팀에 남긴다. */
    public void forgetEmail() {
        this.email = null;
    }

    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** 대기: 수락도 폐기도 안 됨. 자동 참여(로그인·가입)와 다시 보내기의 대상이다. */
    public boolean isPending() {
        return acceptedAt == null && revokedAt == null;
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

    public Long getInvitedBy() {
        return invitedBy;
    }

    public Long getAcceptedAccountId() {
        return acceptedAccountId;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
