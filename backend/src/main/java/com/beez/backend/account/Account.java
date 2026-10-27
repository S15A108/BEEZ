package com.beez.backend.account;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;

/** 계정 (V2 account). 탈퇴하면 email 이 NULL 이 되고 deleted_at 이 찍힌다 (ACC-A10, 후속 PR). */
@Entity
@Table(name = "account")
public class Account extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Account() {
    }

    public static Account register(String email, String passwordHash, String displayName) {
        Account a = new Account();
        a.email = email;
        a.passwordHash = passwordHash;
        a.displayName = displayName;
        return a;
    }

    /**
     * 대소문자·앞뒤 공백만 다른 이메일을 같은 계정으로 본다. 가입·로그인(AuthService)과 초대 대상(MEM-A01)이 같은 규칙을 쓴다.
     */
    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
