package com.beez.backend.auth;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.auth.recovery.PasswordRecoveryService;
import com.beez.backend.common.access.ProjectAccessChecker;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.auth.CallerContext;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인한 본인의 비밀번호 확인·변경 (S15P31A108-171, ACC-A06·A10). 가입·로그인(AuthService)과 나눠 둔 것은
 * 초대 자동 참여(-220)가 AuthService 의 가입·로그인 흐름을 고치기 때문이다 — 두 작업이 같은 파일을 건드리지 않게 한다.
 * 정책 검사는 가입과 같은 {@link PasswordPolicy} 를 쓴다.
 */
@Service
public class AccountPasswordService {

    private static final Logger log = LoggerFactory.getLogger(AccountPasswordService.class);

    private final ProjectAccessChecker access;
    private final AccountRepository accounts;
    private final PasswordEncoder encoder;
    private final PasswordRecoveryService recovery;

    public AccountPasswordService(ProjectAccessChecker access, AccountRepository accounts, PasswordEncoder encoder,
                                  PasswordRecoveryService recovery) {
        this.access = access;
        this.accounts = accounts;
        this.encoder = encoder;
        this.recovery = recovery;
    }

    /**
     * ACC-A06. 현재 비밀번호가 맞아야 바꾼다. 임시 비밀번호로 로그인한 사람은 그 임시 비밀번호가 현재 비밀번호다. 틀리면 400 current_password_mismatch (401 이 아니다 — 세션은 유효하다).
     * 다른 세션 정리는 커밋 뒤 웹 계층(MeController)이 한다.
     *
     * @return 바꾼 계정 (세션 정리에 계정 ID 를 쓴다)
     */
    @Transactional
    public Account change(CallerContext caller, String currentPassword, String newPassword) {
        Account me = access.requireUser(caller);
        if (!matches(me, currentPassword)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "current_password_mismatch", "현재 비밀번호가 맞지 않습니다");
        }
        PasswordPolicy.check(newPassword);
        me.changePasswordHash(encoder.encode(newPassword));
        accounts.saveAndFlush(me);
        // 받아 두고 쓰지 않은 임시 비밀번호가 바꾼 뒤에도 통하지 않게 닫는다 (must_change_password 는 changePasswordHash 가 푼다)
        recovery.closeOpen(me.getId());
        log.info("비밀번호 변경: account_id={}", me.getId());
        return me;
    }

    /**
     * 본인 확인용 비밀번호 대조 (ACC-A06 현재 비밀번호, ACC-A10 탈퇴). 정책상 나올 수 없는 길이·읽을 수 없는 해시는 불일치로 본다.
     * bcrypt 는 72바이트를 넘으면 예외를 던지므로 길이를 먼저 본다 (AuthService.authenticate 와 같은 규칙).
     */
    public boolean matches(Account account, String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES) {
            return false;
        }
        try {
            return encoder.matches(raw, account.getPasswordHash());
        } catch (IllegalArgumentException e) {
            log.warn("unreadable password hash, treated as mismatch: account_id={}", account.getId());
            return false;
        }
    }
}
