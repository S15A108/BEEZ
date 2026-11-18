package com.beez.backend.auth.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beez.backend.auth.recovery.TemporaryPasswordRepository;
import com.beez.backend.auth.verification.EmailVerificationRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** 행 정리의 표별 실패 분리 (-273). DB 없이 본다. */
class AuthRowCleanupJobTest {

    /** 깨지면: email_verification 삭제가 실패한 날 temporary_password 행도 함께 남는다 (주석 "한쪽이 실패해도 다른 쪽은 지운다"). */
    @Test
    void 한_표가_실패해도_다른_표는_지운다() {
        EmailVerificationRepository verifications = mock(EmailVerificationRepository.class);
        TemporaryPasswordRepository temporaries = mock(TemporaryPasswordRepository.class);
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(verifications.deleteEndedBefore(any())).thenThrow(new QueryTimeoutException("lock timeout"));
        when(temporaries.deleteEndedBefore(any())).thenReturn(3);
        AuthRowCleanupJob job = new AuthRowCleanupJob(verifications, temporaries,
                new AuthGuardProperties(10, Duration.ofHours(1), Duration.ofDays(7)), manager);

        AuthRowCleanupJob.Purged purged = job.purge(Instant.parse("2026-11-09T19:30:00Z"));

        assertThat(purged.emailVerifications()).as("실패한 표").isEqualTo(-1);
        assertThat(purged.temporaryPasswords()).isEqualTo(3);
    }
}
