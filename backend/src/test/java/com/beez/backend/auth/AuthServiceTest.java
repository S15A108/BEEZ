package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.api.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** DelegatingPasswordEncoder 는 {bcrypt} 같은 접두어가 없는 해시에 예외를 던진다 — 로그인이 500 으로 새지 않아야 한다. */
class AuthServiceTest {

    private final AccountRepository accounts = mock(AccountRepository.class);
    private final AuthService auth = new AuthService(accounts, PasswordEncoderFactories.createDelegatingPasswordEncoder());

    @Test
    void storedHashWithoutEncoderPrefix_isInvalidCredentials_not500() {
        Account seeded = Account.register("seed@example.com", "x", "시드");
        when(accounts.findByEmailAndDeletedAtIsNull("seed@example.com")).thenReturn(Optional.of(seeded));

        assertThatThrownBy(() -> auth.authenticate("seed@example.com", "anything-1"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("invalid_credentials"));
    }

    @Test
    void passwordOver72Bytes_isInvalidCredentials_withoutHashComparison_forKnownAndUnknownEmail() {
        // bcrypt 는 72바이트를 넘는 원문에 예외를 던진다. 비교에 넘기면 "unreadable password hash" WARN 이 잘못 쌓인다
        PasswordEncoder encoder = spy(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        AuthService service = new AuthService(accounts, encoder);
        Account known = Account.register("known@example.com", encoder.encode("secret-pass-1"), "있음");
        when(accounts.findByEmailAndDeletedAtIsNull("known@example.com")).thenReturn(Optional.of(known));
        String tooLong = "a1" + "x".repeat(PasswordPolicy.MAX_BYTES - 1);   // 73바이트

        for (String email : new String[] {"known@example.com", "unknown@example.com"}) {
            assertThatThrownBy(() -> service.authenticate(email, tooLong))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("invalid_credentials"));
        }
        verify(encoder, never()).matches(eq(tooLong), any());
        // 정책상 나올 수 없는 길이라 계정 조회도 하지 않는다
        verify(accounts, never()).findByEmailAndDeletedAtIsNull(any());
    }
}
