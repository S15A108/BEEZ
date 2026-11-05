package com.beez.backend.auth.recovery;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 비밀번호 찾기 요청 한 번 (V27 temporary_password, ACC-A07). 계정이 맞으면 임시 비밀번호 해시를 담고,
 * 아니면 발송 한도를 세려고 남기는 빈 행이다(바로 닫힘).
 */
@Entity
@Table(name = "temporary_password")
public class TemporaryPassword extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected TemporaryPassword() {
    }

    /** 계정이 맞은 요청: 메일로 보낼 임시 비밀번호. */
    public static TemporaryPassword issue(String normalizedEmail, long accountId, String passwordHash,
                                          Instant expiresAt) {
        TemporaryPassword t = new TemporaryPassword();
        t.email = normalizedEmail;
        t.accountId = accountId;
        t.passwordHash = passwordHash;
        t.expiresAt = expiresAt;
        return t;
    }

    /** 계정이 없거나 이름이 틀린 요청: 한도만 센다. 처음부터 닫혀 있어 로그인에 쓰이지 않는다. */
    public static TemporaryPassword unmatched(String normalizedEmail, Instant now) {
        TemporaryPassword t = new TemporaryPassword();
        t.email = normalizedEmail;
        t.expiresAt = now;
        t.closedAt = now;
        return t;
    }

    /** 아직 로그인에 쓸 수 있는지: 닫히지 않았고 만료 전. */
    public boolean usableAt(Instant now) {
        return passwordHash != null && closedAt == null && now.isBefore(expiresAt);
    }

    /** 이것으로 로그인했다. 다시 쓸 수 없다. */
    public void use(Instant now) {
        this.usedAt = now;
        this.closedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
