package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.auth.recovery.PasswordRecoveryService;
import com.beez.backend.common.access.ProjectAccessChecker;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 본인 확인 대조(ACC-A06 현재 비밀번호·ACC-A10 탈퇴)가 500 으로 새지 않는지. 로그인(AuthServiceTest)과 같은 함정이다. */
class AccountPasswordServiceTest {

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final AccountPasswordService passwords = new AccountPasswordService(mock(ProjectAccessChecker.class),
            mock(AccountRepository.class), encoder, mock(PasswordRecoveryService.class));

    /**
     * 깨지면: 72바이트를 넘는 입력이나 접두어 없는 해시({bcrypt} 없음·NULL — SQL 시드)에서 bcrypt 가 던지는 예외가 500 이 된다.
     * 400(현재 비밀번호 불일치·invalid_credentials)이어야 한다.
     */
    @Test
    void over72Bytes_unreadableHash_andNull_areMismatch_notException() {
        Account account = Account.register("a@example.com", encoder.encode("right-pass-1"), "이름");
        assertThat(passwords.matches(account, "right-pass-1")).isTrue();
        assertThat(passwords.matches(account, "가".repeat(25))).as("75바이트").isFalse();
        assertThat(passwords.matches(account, null)).isFalse();

        Account seeded = Account.register("s@example.com", "no-prefix-hash", "시드");
        assertThat(passwords.matches(seeded, "anything-1")).isFalse();
    }
}
