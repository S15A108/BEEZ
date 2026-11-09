package com.beez.backend.auth.recovery;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.concurrent.LogThrottle;
import com.beez.backend.common.mail.MailGateway;
import com.beez.backend.common.mail.MailMessage;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 비밀번호 찾기 — 임시 비밀번호 (S15P31A108-171, ACC-A07 B안). 재설정 링크(ACC-A08·A09)는 명세에서 폐지 예정이라 만들지 않는다.
 *
 * <ul>
 *   <li><b>가입 여부를 드러내지 않는다.</b> 계정이 없거나 이름이 틀려도 같은 202 다. 발송 한도(429)도 이메일마다 똑같이 세려고
 *       맞지 않은 요청도 행을 남긴다. 해시 계산도 똑같이 하고, 메일은 응답과 따로(비동기) 보내 응답 시간으로도 드러나지 않게 한다.
 *       그래서 메일 발송 실패는 응답(503)으로 알리지 않는다 — 발급을 닫고 WARN 로그만 남긴다.</li>
 *   <li><b>발급해도 기존 비밀번호는 그대로다.</b> 이메일·이름만 아는 사람이 요청해 계정 주인을 잠그지 못하게 한다.
 *       임시 비밀번호로 로그인하는 순간 그것이 비밀번호가 되고 must_change_password 가 붙는다({@link #redeem}).</li>
 *   <li>임시 비밀번호는 계정당 하나만 열려 있고, 한 번 로그인에 쓰면 닫힌다. 새로 발급하면 이전 것은 바로 무효다.
 *       만료·닫힌 임시 비밀번호로는 로그인할 수 없다(401 invalid_credentials).</li>
 *   <li>원문은 메일에만 있다. DB 에는 bcrypt 해시, 로그에는 요청 ID 만 남긴다.</li>
 * </ul>
 */
@Service
public class PasswordRecoveryService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PasswordRecoveryService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 헷갈리는 글자(0·O·1·l·I)를 뺀 영문·숫자 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    /** 임시 비밀번호 길이. 비밀번호 정책(8자 이상, 영문·숫자 포함)을 넉넉히 넘는다 */
    static final int LENGTH = 12;
    /** V27 계정당 열린 임시 비밀번호 하나 */
    private static final String OPEN_UNIQUE = "ux_temporary_password_open";
    static final String ACCEPTED_MESSAGE = "입력한 정보가 맞으면 임시 비밀번호를 메일로 보냅니다.";

    private final TemporaryPasswordRepository temporaries;
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final MailGateway mail;
    private final TransactionTemplate tx;
    private final PasswordRecoveryProperties props;
    /** 메일 발송용. SMTP 를 기다리는 동안 요청 스레드·DB 연결을 쥐지 않고, 응답 시간이 계정 유무로 달라지지 않게 한다 */
    private final ExecutorService mailSender = Executors.newVirtualThreadPerTaskExecutor();
    /** 서버 전체 상한 도달 WARN 은 10분에 한 번만 (공격 중 요청마다 찍히지 않게, -273) */
    private final LogThrottle globalLimitWarn = new LogThrottle(Duration.ofMinutes(10));

    public PasswordRecoveryService(TemporaryPasswordRepository temporaries, AccountRepository accounts,
                                   PasswordEncoder encoder, MailGateway mail, PlatformTransactionManager manager,
                                   PasswordRecoveryProperties props) {
        this.temporaries = temporaries;
        this.accounts = accounts;
        this.encoder = encoder;
        this.mail = mail;
        this.tx = new TransactionTemplate(manager);
        this.props = props;
    }

    /** ACC-A07. 결과와 관계없이 같은 안내 문구를 돌려준다. 한도를 넘으면 429 rate_limited. */
    public String request(String rawEmail, String rawName) {
        String email = Account.normalizeEmail(rawEmail);
        String name = rawName.strip();
        Instant now = Instant.now();
        // 한도에 걸릴 요청이 bcrypt 를 쓰지 않게 먼저 본다. 트랜잭션 안에서 한 번 더 본다
        checkAllowed(email, now);
        Optional<Account> target = accounts.findByEmailAndDeletedAtIsNull(email)
                .filter(a -> a.getDisplayName() != null && a.getDisplayName().strip().equals(name));
        String temporary = generate();
        String hash = encoder.encode(temporary);   // 계정이 없어도 계산한다 — 응답 시간을 맞춘다

        TemporaryPassword saved;
        try {
            saved = tx.execute(status -> {
                checkAllowed(email, now);
                if (target.isEmpty()) {
                    return temporaries.save(TemporaryPassword.unmatched(email, now));
                }
                temporaries.closeOpen(target.get().getId(), now);
                return temporaries.saveAndFlush(
                        TemporaryPassword.issue(email, target.get().getId(), hash, now.plus(props.ttl())));
            });
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, OPEN_UNIQUE)) {
                throw e;
            }
            // 같은 계정으로 동시에 요청했다: 먼저 들어간 요청이 발급을 차지했다. 응답은 똑같이 202
            return ACCEPTED_MESSAGE;
        }
        if (target.isPresent()) {
            long id = saved.getId();
            mailSender.execute(() -> deliver(id, email, temporary));
        }
        return ACCEPTED_MESSAGE;
    }

    /**
     * 로그인(ACC-A02)에서 비밀번호가 틀렸을 때 부른다. 쓸 수 있는 임시 비밀번호와 맞으면 그것을 계정 비밀번호로 올리고
     * must_change_password 를 붙인 뒤 true. 로그인 트랜잭션 안에서 부른다 — 계정 변경은 그 트랜잭션이 저장한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean redeem(Account account, String raw) {
        Instant now = Instant.now();
        Optional<TemporaryPassword> open = temporaries.findOpenForUpdate(account.getId())
                .filter(t -> t.usableAt(now))
                .filter(t -> encoder.matches(raw, t.getPasswordHash()));
        if (open.isEmpty()) {
            return false;
        }
        open.get().use(now);
        account.adoptTemporaryPassword(open.get().getPasswordHash());
        log.info("임시 비밀번호로 로그인: account_id={} temporary_password_id={}", account.getId(), open.get().getId());
        return true;
    }

    /** 비밀번호 변경(ACC-A06)과 같은 트랜잭션에서 부른다. 바꾼 뒤에 이전에 받은 임시 비밀번호가 통하지 않게 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeOpen(long accountId) {
        temporaries.closeOpen(accountId, Instant.now());
    }

    /** 회원 탈퇴(ACC-A10, -277)와 같은 트랜잭션에서 부른다. 그 이메일의 요청 행을 모두 지우고 지운 수를 돌려준다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int forgetOnAccountWithdrawal(String normalizedEmail) {
        return temporaries.deleteByEmail(normalizedEmail);
    }

    /** 임시 비밀번호 원문. 비밀번호 정책(영문·숫자 각 1자 이상)을 만족할 때까지 다시 뽑는다. */
    public static String generate() {
        while (true) {
            StringBuilder sb = new StringBuilder(LENGTH);
            for (int i = 0; i < LENGTH; i++) {
                sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            String s = sb.toString();
            if (s.chars().anyMatch(Character::isLetter) && s.chars().anyMatch(Character::isDigit)) {
                return s;
            }
        }
    }

    private void deliver(long id, String email, String temporary) {
        try {
            mail.send(new MailMessage(email, "[BEEZ] 임시 비밀번호", mailText(temporary)));
            log.info("임시 비밀번호 발송: temporary_password_id={}", id);
        } catch (RuntimeException e) {
            // 받지 못한 임시 비밀번호가 열린 채 남지 않게 닫는다. 사용자는 다시 요청하면 된다(다시 받기 간격만 기다림)
            log.warn("임시 비밀번호 메일 발송 실패: temporary_password_id={} exception_type={}", id,
                    e.getClass().getSimpleName());
            try {
                tx.executeWithoutResult(status -> temporaries.findById(id).ifPresent(t -> {
                    if (t.usableAt(Instant.now())) {
                        temporaries.closeOpen(t.getAccountId(), Instant.now());
                    }
                }));
            } catch (RuntimeException closeFailure) {
                log.warn("발송 실패한 임시 비밀번호를 닫지 못함: temporary_password_id={} exception_type={}", id,
                        closeFailure.getClass().getSimpleName());
            }
        }
    }

    private String mailText(String temporary) {
        return """
                BEEZ 임시 비밀번호입니다.

                %s

                %d분 안에 이 비밀번호로 로그인한 뒤 회원 설정에서 비밀번호를 바꾸세요.
                직접 요청하지 않았다면 이 메일을 무시하세요. 기존 비밀번호는 그대로 쓸 수 있습니다.
                """.formatted(temporary, props.ttl().toMinutes());
    }

    /** 다시 받기 간격, 이메일당 요청 한도, 서버 전체 발송 상한. 넘으면 429 rate_limited. */
    private void checkAllowed(String email, Instant now) {
        temporaries.findFirstByEmailOrderByIdDesc(email).ifPresent(latest -> {
            if (now.isBefore(latest.getCreatedAt().plus(props.resendInterval()))) {
                throw rateLimited("다시 요청은 잠시 뒤에 할 수 있습니다");
            }
        });
        if (temporaries.countByEmailAndCreatedAtAfter(email, now.minus(props.sendLimitWindow())) >= props.sendLimit()) {
            throw rateLimited("이 이메일로 요청할 수 있는 횟수를 넘었습니다. 잠시 뒤에 다시 시도하세요");
        }
        if (temporaries.countByAccountIdIsNotNullAndCreatedAtAfter(now.minus(props.sendLimitWindow()))
                >= props.globalSendLimit()) {
            long skipped = globalLimitWarn.tryPass(now);
            if (skipped >= 0) {
                log.warn("임시 비밀번호 메일 서버 전체 상한 도달: limit={} window={} suppressed_since_last={}",
                        props.globalSendLimit(), props.sendLimitWindow(), skipped);
            }
            throw rateLimited("지금은 요청이 많습니다. 잠시 뒤에 다시 시도하세요");
        }
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException c) {
                return constraint.equalsIgnoreCase(c.getConstraintName());
            }
        }
        return false;
    }

    private static ApiException rateLimited(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
    }

    @Override
    public void destroy() {
        mailSender.close();   // 보내는 중인 메일은 끝까지 보낸다
    }
}
