package com.beez.backend.auth;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입(ACC-A01)·자격 증명 확인(ACC-A02). 세션 발급은 웹 계층(AuthController) 몫이다. */
@Service
public class AuthService {

    /** V2 account.email UNIQUE 의 Postgres 기본 제약 이름 */
    private static final String EMAIL_UNIQUE = "account_email_key";

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    /** 없는 이메일에도 같은 시간만큼 해시 비교를 해서, 응답 시간으로 가입 여부가 드러나지 않게 한다. */
    private static final String DUMMY_RAW = "beez-timing-equalizer-0";
    private final String dummyHash;

    public AuthService(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.dummyHash = encoder.encode(DUMMY_RAW);
    }

    @Transactional
    public Account register(String email, String password, String name) {
        PasswordPolicy.check(password);
        Account account = Account.register(normalizeEmail(email), encoder.encode(password), name.trim());
        try {
            // 중복 판정은 account.email UNIQUE 에 맡긴다 — 미리 조회하면 동시 가입 사이에 틈이 생긴다
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, EMAIL_UNIQUE)) {
                throw new ApiException(HttpStatus.CONFLICT, "email_already_exists", "이미 등록된 이메일");
            }
            throw e;   // 다른 제약 위반은 공통 처리(GlobalExceptionHandler)로 보낸다
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

    /** 이메일·비밀번호가 맞는 활성 계정. 틀리면 가입 여부와 관계없이 같은 401. */
    @Transactional(readOnly = true)
    public Account authenticate(String email, String password) {
        Optional<Account> found = accounts.findByEmailAndDeletedAtIsNull(normalizeEmail(email));
        if (password.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
            // 가입 정책상 나올 수 없는 길이라 무조건 불일치. bcrypt 는 이 길이를 받지 않으므로 더미 비교로 응답 시간만 맞춘다
            encoder.matches(DUMMY_RAW, dummyHash);
            throw invalidCredentials();
        }
        boolean matches = matches(password, found.map(Account::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            throw invalidCredentials();
        }
        return found.get();
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "이메일 또는 비밀번호 불일치");
    }

    /** 저장된 해시를 읽을 수 없으면({bcrypt} 같은 접두어 없음·NULL — SQL 시드 등) 불일치로 본다. 500 으로 새지 않게 한다. */
    private boolean matches(String raw, String storedHash) {
        try {
            return encoder.matches(raw, storedHash);
        } catch (IllegalArgumentException e) {
            log.warn("unreadable password hash, treated as mismatch: {}", e.getMessage());
            return false;
        }
    }

    /** 대소문자·앞뒤 공백만 다른 이메일을 같은 계정으로 본다. */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
