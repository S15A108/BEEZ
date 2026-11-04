package com.beez.backend.auth.verification;

import com.beez.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 가입 이메일 인증 요청 한 번 (V25 email_verification, ACC-A11·A12).
 * 코드 단계: 코드 해시·만료·틀린 횟수. 확인에 성공하면 같은 행에 가입 토큰(verified_token) 해시를 채운다.
 * 판정 규칙은 시각을 받는 메서드로 두어 DB 없이 시험한다 (EmailVerificationTest).
 */
@Entity
@Table(name = "email_verification")
public class EmailVerification extends BaseTimeEntity {

    /** 코드 확인 결과. MISMATCH 만 남은 시도 횟수를 함께 준다. */
    public enum Outcome {
        CONFIRMED, MISMATCH, EXPIRED, TOO_MANY_ATTEMPTS
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** API 의 verification_id. 추측할 수 없는 난수 (SecretTokens.generate). */
    @Column(name = "public_id")
    private String publicId;

    private String email;

    @Column(name = "code_hash")
    private String codeHash;

    @Column(name = "code_expires_at")
    private Instant codeExpiresAt;

    @Column(name = "failed_attempts")
    private int failedAttempts;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "token_consumed_at")
    private Instant tokenConsumedAt;

    protected EmailVerification() {
    }

    public static EmailVerification issue(String publicId, String normalizedEmail, String codeHash,
                                          Instant codeExpiresAt) {
        EmailVerification v = new EmailVerification();
        v.publicId = publicId;
        v.email = normalizedEmail;
        v.codeHash = codeHash;
        v.codeExpiresAt = codeExpiresAt;
        return v;
    }

    /**
     * 코드를 지금 확인할 수 있는가. 아니면 그 이유를 돌려주고, 확인할 수 있으면 null.
     * 순서: 이미 쓴 코드(재사용) → 틀린 횟수 초과 → 다시 받기로 닫힘 → 시간 만료.
     * 틀린 횟수 초과를 닫힘보다 먼저 봐서, 초과로 닫힌 요청은 계속 429 로 답한다.
     */
    public Outcome rejectBeforeMatch(Instant now, int maxAttempts) {
        if (tokenHash != null) {
            return Outcome.EXPIRED;
        }
        if (failedAttempts >= maxAttempts) {
            return Outcome.TOO_MANY_ATTEMPTS;
        }
        if (closedAt != null || !now.isBefore(codeExpiresAt)) {
            return Outcome.EXPIRED;
        }
        return null;
    }

    /** 틀린 입력 한 번. 한도에 닿으면 이 요청을 닫고 TOO_MANY_ATTEMPTS. */
    public Outcome recordMismatch(Instant now, int maxAttempts) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            closedAt = now;
            return Outcome.TOO_MANY_ATTEMPTS;
        }
        return Outcome.MISMATCH;
    }

    /** 코드가 맞았다. 코드를 닫고(재사용 불가) 가입 토큰을 붙인다. */
    public void confirm(Instant now, String verifiedTokenHash, Instant verifiedTokenExpiresAt) {
        closedAt = now;
        tokenHash = verifiedTokenHash;
        tokenExpiresAt = verifiedTokenExpiresAt;
    }

    /** 이 이메일로 가입할 때 쓸 수 있는 토큰인가: 아직 안 썼고, 만료 전이고, 인증한 이메일과 같다. */
    public boolean tokenUsableFor(String normalizedEmail, Instant now) {
        return tokenHash != null && tokenConsumedAt == null && now.isBefore(tokenExpiresAt)
                && email.equals(normalizedEmail);
    }

    /** 가입 성공. 토큰은 다시 쓸 수 없다. */
    public void consumeToken(Instant now) {
        tokenConsumedAt = now;
    }

    public int remainingAttempts(int maxAttempts) {
        return Math.max(0, maxAttempts - failedAttempts);
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getEmail() {
        return email;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Instant getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getTokenExpiresAt() {
        return tokenExpiresAt;
    }
}
