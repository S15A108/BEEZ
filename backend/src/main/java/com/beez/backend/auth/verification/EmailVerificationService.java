package com.beez.backend.auth.verification;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.auth.verification.EmailVerification.Outcome;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.SecretTokens;
import com.beez.backend.common.mail.MailDeliveryException;
import com.beez.backend.common.mail.MailGateway;
import com.beez.backend.common.mail.MailMessage;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 가입 이메일 인증 (S15P31A108-219). ACC-A11 코드 보내기, ACC-A12 코드 확인, ACC-A01 가입 때 토큰 소비.
 *
 * <ul>
 *   <li>코드는 6자리라 경우의 수가 100만 개뿐이다. SHA-256 이면 DB 가 새는 순간 바로 풀리므로 비밀번호와 같은 bcrypt(PasswordEncoder)로
 *       해시한다. 조회는 ID 로 하므로 느린 해시여도 된다.</li>
 *   <li>verified_token 은 32바이트 난수라 SHA-256(SecretTokens)으로 저장하고 해시로 찾는다 — 초대·에이전트 토큰과 같다.</li>
 *   <li>메일은 트랜잭션 밖에서 보낸다. SMTP 를 기다리는 동안 DB 연결을 쥐고 있지 않게 하려는 것이다.
 *       보내지 못하면 방금 만든 요청을 지워 다시 받기 간격·발송 한도에 세지 않는다.</li>
 *   <li>코드·토큰 원문은 응답(토큰은 ACC-A12 응답에 한 번)·로그에 남기지 않는다.</li>
 * </ul>
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 명세 ACC-A12 예시 "evt_example". 다른 토큰과 섞여 들어와도 눈으로 구분된다 */
    static final String TOKEN_PREFIX = "evt_";

    private final EmailVerificationRepository verifications;
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final MailGateway mail;
    private final TransactionTemplate tx;
    private final EmailVerificationProperties props;

    public EmailVerificationService(EmailVerificationRepository verifications, AccountRepository accounts,
                                    PasswordEncoder encoder, MailGateway mail, PlatformTransactionManager manager,
                                    EmailVerificationProperties props) {
        this.verifications = verifications;
        this.accounts = accounts;
        this.encoder = encoder;
        this.mail = mail;
        this.tx = new TransactionTemplate(manager);
        this.props = props;
    }

    public record Sent(Long verificationId, Instant expiresAt, Instant resendAvailableAt) {
    }

    public record Confirmed(String verifiedToken, String email, Instant expiresAt) {
    }

    /** ACC-A11. 이전 코드는 바로 무효가 된다. */
    public Sent send(String rawEmail) {
        String email = Account.normalizeEmail(rawEmail);
        if (accounts.findByEmailAndDeletedAtIsNull(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_exists", "이미 등록된 이메일");
        }
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        String codeHash = encoder.encode(code);   // bcrypt 는 느리므로 트랜잭션 밖에서
        Instant now = Instant.now();

        EmailVerification saved;
        try {
            saved = tx.execute(status -> {
                verifications.findFirstByEmailOrderByIdDesc(email).ifPresent(latest -> {
                    if (now.isBefore(latest.getCreatedAt().plus(props.resendInterval()))) {
                        throw rateLimited("다시 받기는 잠시 뒤에 할 수 있습니다");
                    }
                });
                if (verifications.countByEmailAndCreatedAtAfter(email, now.minus(props.sendLimitWindow()))
                        >= props.sendLimit()) {
                    throw rateLimited("이 이메일로 보낼 수 있는 횟수를 넘었습니다. 잠시 뒤에 다시 시도하세요");
                }
                verifications.closeOpen(email, now);
                return verifications.saveAndFlush(
                        EmailVerification.issue(email, codeHash, now.plus(props.codeTtl())));
            });
        } catch (DataIntegrityViolationException e) {
            // 같은 이메일로 동시에 보냈다: 먼저 들어간 요청이 열린 코드를 차지했다 (ux_email_verification_open)
            throw rateLimited("다시 받기는 잠시 뒤에 할 수 있습니다");
        }

        try {
            mail.send(new MailMessage(email, "[BEEZ] 가입 인증 코드", codeMail(code)));
        } catch (MailDeliveryException e) {
            discard(saved.getId());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail_send_failed",
                    "인증 메일을 보내지 못했습니다. 잠시 뒤에 다시 시도하세요");
        }
        log.info("가입 인증 코드 발송: verification_id={}", saved.getId());
        return new Sent(saved.getId(), saved.getCodeExpiresAt(), saved.getCreatedAt().plus(props.resendInterval()));
    }

    /** ACC-A12. 틀린 횟수는 오류 응답이어도 남아야 하므로 판정을 커밋한 뒤 오류로 바꾼다. */
    public Confirmed confirm(long verificationId, String code) {
        String token = TOKEN_PREFIX + SecretTokens.generate();
        record Result(Outcome outcome, int remaining, EmailVerification verification) {
        }
        Result result = tx.execute(status -> {
            EmailVerification v = verifications.findByIdForUpdate(verificationId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found", "없는 인증 요청"));
            Instant now = Instant.now();
            Outcome rejected = v.rejectBeforeMatch(now, props.maxAttempts());
            if (rejected != null) {
                return new Result(rejected, 0, v);
            }
            if (!encoder.matches(code, v.getCodeHash())) {
                Outcome outcome = v.recordMismatch(now, props.maxAttempts());
                return new Result(outcome, v.remainingAttempts(props.maxAttempts()), v);
            }
            v.confirm(now, SecretTokens.hash(token), now.plus(props.tokenTtl()));
            return new Result(Outcome.CONFIRMED, 0, v);
        });

        return switch (result.outcome()) {
            case CONFIRMED -> new Confirmed(token, result.verification().getEmail(),
                    result.verification().getTokenExpiresAt());
            case MISMATCH -> throw new ApiException(HttpStatus.BAD_REQUEST, "verification_code_mismatch",
                    "인증 코드가 맞지 않습니다", Map.of("remaining_attempts", result.remaining()));
            case EXPIRED -> throw new ApiException(HttpStatus.GONE, "verification_expired",
                    "인증 코드가 만료됐거나 더 쓸 수 없습니다. 코드를 다시 받으세요");
            case TOO_MANY_ATTEMPTS -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "틀린 횟수를 넘었습니다. 코드를 다시 받으세요");
        };
    }

    /**
     * ACC-A01 가입 트랜잭션 안에서 부른다. 토큰을 쓸 수 있으면 소비하고, 아니면 400 email_not_verified.
     * 가입이 뒤에서 실패하면(409 등) 같은 트랜잭션이 되돌려져 토큰도 다시 쓸 수 있다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void consumeForRegistration(String verifiedToken, String normalizedEmail) {
        Instant now = Instant.now();
        EmailVerification v = verifications.findByTokenHashForUpdate(SecretTokens.hash(verifiedToken))
                .filter(found -> found.tokenUsableFor(normalizedEmail, now))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "email_not_verified",
                        "이메일 인증이 없거나 만료됐거나 다른 이메일의 인증입니다"));
        v.consumeToken(now);
    }

    private void discard(Long id) {
        try {
            tx.executeWithoutResult(status -> verifications.deleteById(id));
        } catch (RuntimeException e) {
            // 지우지 못하면 이 요청이 다시 받기 간격에 세어질 뿐이다. 원래 오류(503)를 그대로 돌려준다
            log.warn("발송 실패한 인증 요청을 지우지 못함: verification_id={} exception_type={}", id,
                    e.getClass().getSimpleName());
        }
    }

    private String codeMail(String code) {
        return """
                BEEZ 가입 인증 코드입니다.

                %s

                %d분 안에 가입 화면에 입력하세요. 직접 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(code, props.codeTtl().toMinutes());
    }

    private static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
    }
}
