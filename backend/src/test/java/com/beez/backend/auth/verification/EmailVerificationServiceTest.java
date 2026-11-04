package com.beez.backend.auth.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beez.backend.account.AccountRepository;
import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.mail.MailDeliveryException;
import com.beez.backend.common.mail.MailGateway;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 발송 실패·틀린 코드가 명세의 오류 응답으로 바뀌는지 (ACC-A11·A12). DB·SMTP 없이 본다. */
class EmailVerificationServiceTest {

    private final EmailVerificationRepository verifications = mock(EmailVerificationRepository.class);
    private final AccountRepository accounts = mock(AccountRepository.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final MailGateway mail = mock(MailGateway.class);
    private final EmailVerificationProperties props = new EmailVerificationProperties(Duration.ofMinutes(5),
            Duration.ofSeconds(60), 5, Duration.ofHours(1), 5, Duration.ofMinutes(30));
    private final EmailVerificationService service;

    EmailVerificationServiceTest() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new EmailVerificationService(verifications, accounts, encoder, mail, manager, props);
        when(encoder.encode(anyString())).thenReturn("hashed");
    }

    private static EmailVerification saved(long id) {
        EmailVerification v = EmailVerification.issue("a@example.com", "hashed", Instant.now().plusSeconds(300));
        ReflectionTestUtils.setField(v, "id", id);
        ReflectionTestUtils.setField(v, "createdAt", Instant.now());
        return v;
    }

    // 깨지면: SMTP 가 실패했는데 201 이 나가거나(사용자는 오지 않을 코드를 기다림), 실패한 요청이 남아 다시 받기가 1분 막힌다
    // (완료 조건 "메일 발송 실패 시 정해진 오류 응답")
    @Test
    void 메일_발송_실패는_503_mail_send_failed_이고_만든_요청을_지운다() {
        when(accounts.findByEmailAndDeletedAtIsNull("a@example.com")).thenReturn(Optional.empty());
        when(verifications.findFirstByEmailOrderByIdDesc("a@example.com")).thenReturn(Optional.empty());
        when(verifications.saveAndFlush(any())).thenReturn(saved(41L));
        doThrow(new MailDeliveryException("down")).when(mail).send(any());

        assertThatThrownBy(() -> service.send("A@example.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getCode()).isEqualTo("mail_send_failed");
                });
        verify(verifications).deleteById(41L);
    }

    // 깨지면: 화면이 "남은 시도 N번"을 보여 줄 error.details.remaining_attempts 가 빠진다 (ACC-A12 오류표)
    @Test
    void 틀린_코드는_400_과_남은_시도() {
        EmailVerification v = saved(41L);
        when(verifications.findByIdForUpdate(41L)).thenReturn(Optional.of(v));
        when(encoder.matches("000000", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> service.confirm(41L, "000000"))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("verification_code_mismatch");
                    assertThat(e.getDetails()).containsEntry("remaining_attempts", 4);
                });
    }
}
