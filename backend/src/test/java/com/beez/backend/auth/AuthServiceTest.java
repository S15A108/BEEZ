package com.beez.backend.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beez.backend.account.Account;
import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.api.ApiException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

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
}
