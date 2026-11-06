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

/** 계정 (V2 account). 탈퇴하면 email 이 NULL 이 되고 deleted_at 이 찍힌다 (ACC-A10, {@link #withdraw}). */
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

    /** 임시 비밀번호(ACC-A07)로 로그인했으면 true. 비밀번호 변경(ACC-A06)으로 풀린다 (V27). */
    @Column(name = "must_change_password")
    private boolean mustChangePassword;

    protected Account() {
    }

    public static Account register(String email, String passwordHash, String displayName) {
        Account a = new Account();
        a.email = email;
        a.passwordHash = passwordHash;
        a.displayName = displayName;
        return a;
    }

    /** 탈퇴한 계정의 표시 이름. 작업·회의·문서 기록이 이 이름으로 남는다 (ACC-A10 "탈퇴한 사용자로 보존"). */
    public static final String WITHDRAWN_NAME = "탈퇴한 사용자";

    /** ACC-A05. 값은 서비스가 정리해서 넘긴다 — avatar null 은 지우기다. */
    public void changeProfile(String displayName, String avatarUrl) {
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
    }

    /** ACC-A06. 인코딩한 해시를 받는다. 임시 비밀번호로 로그인한 뒤 바꾼 것이면 변경 요구도 풀린다. */
    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
    }

    /** ACC-A07·A02. 임시 비밀번호로 로그인하면 그것이 비밀번호가 되고, 바꾸라는 표시가 붙는다. */
    public void adoptTemporaryPassword(String temporaryPasswordHash) {
        this.passwordHash = temporaryPasswordHash;
        this.mustChangePassword = true;
    }

    /**
     * ACC-A10. 개인정보(이메일·이름·아바타·비밀번호 해시)를 지우고 탈퇴 시각을 찍는다. 행은 남긴다 — 작업·토큰·문서 기록이 계정 ID 를 가리킨다.
     * email 이 NULL 이 되므로 UNIQUE 에 걸리지 않아 같은 이메일로 새로 가입할 수 있다(새 계정 ID).
     */
    public void withdraw(Instant now) {
        this.email = null;
        this.displayName = WITHDRAWN_NAME;
        this.avatarUrl = null;
        this.passwordHash = null;
        this.mustChangePassword = false;
        this.deletedAt = now;
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

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
