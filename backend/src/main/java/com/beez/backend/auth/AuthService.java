package com.beez.backend.auth;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.api.ApiException;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원가입(ACC-A01)·자격 증명 확인(ACC-A02). 세션 발급은 웹 계층(AuthController) 몫이다. */
@Service
public class AuthService {

    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    /** 없는 이메일에도 같은 시간만큼 해시 비교를 해서, 응답 시간으로 가입 여부가 드러나지 않게 한다. */
    private final String dummyHash;

    public AuthService(AccountRepository accounts, PasswordEncoder encoder) {
        this.accounts = accounts;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("beez-timing-equalizer-0");
    }

    @Transactional
    public Account register(String email, String password, String name) {
        PasswordPolicy.check(password);
        Account account = Account.register(normalizeEmail(email), encoder.encode(password), name.trim());
        try {
            // 중복 판정은 account.email UNIQUE 에 맡긴다 — 미리 조회하면 동시 가입 사이에 틈이 생긴다
            return accounts.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "email_already_exists", "이미 등록된 이메일");
        }
    }

    /** 이메일·비밀번호가 맞는 활성 계정. 틀리면 가입 여부와 관계없이 같은 401. */
    @Transactional(readOnly = true)
    public Account authenticate(String email, String password) {
        Optional<Account> found = accounts.findByEmailAndDeletedAtIsNull(normalizeEmail(email));
        boolean matches = encoder.matches(password, found.map(Account::getPasswordHash).orElse(dummyHash));
        if (found.isEmpty() || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "이메일 또는 비밀번호 불일치");
        }
        return found.get();
    }

    /** 대소문자·앞뒤 공백만 다른 이메일을 같은 계정으로 본다. */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
