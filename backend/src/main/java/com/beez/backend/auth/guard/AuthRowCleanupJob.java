package com.beez.backend.auth.guard;

import com.beez.backend.auth.recovery.TemporaryPasswordRepository;
import com.beez.backend.auth.verification.EmailVerificationRepository;
import java.time.Instant;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 끝난 지 보존 기간(Q04: 7일)이 지난 email_verification·temporary_password 행을 매일 새벽에 지운다 (S15P31A108-273).
 * 주소·해시가 무기한 남지 않게 한다.
 *
 * <p>"끝난 시각"은 그 행의 만료 시각 중 가장 늦은 것으로 본다 — 행은 늦어도 만료 시각에 끝나므로(닫힘·소비·사용이 더 이를 수
 * 있다) 만료 기준이면 끝난 행을 모두 덮고, 일찍 닫힌 행은 최대 유효 시간(코드 5분·토큰 30분·임시 비밀번호 30분)만큼 늦게 지울 뿐이다. created_at 조건은 같은 뜻이지만
 * 생성 시각 인덱스를 타게 하려고 함께 건다. 보존 기간은 하루 이상이라 발송 한도(최근 1시간 행을 셈)는 영향을 받지 않는다.
 */
@Component
public class AuthRowCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuthRowCleanupJob.class);

    private final EmailVerificationRepository verifications;
    private final TemporaryPasswordRepository temporaries;
    private final AuthGuardProperties props;
    private final TransactionTemplate tx;

    public AuthRowCleanupJob(EmailVerificationRepository verifications, TemporaryPasswordRepository temporaries,
                             AuthGuardProperties props, PlatformTransactionManager manager) {
        this.verifications = verifications;
        this.temporaries = temporaries;
        this.props = props;
        this.tx = new TransactionTemplate(manager);
    }

    public record Purged(int emailVerifications, int temporaryPasswords) {
    }

    @Scheduled(cron = "${beez.auth-guard.row-cleanup-cron}", zone = "Asia/Seoul")
    public void run() {
        purge(Instant.now());
    }

    /**
     * now 기준 보존 기간 전에 끝난 행을 지운다. 표마다 따로 커밋하고 실패도 따로 받는다 — 한쪽이 실패해도 다른 쪽은 지운다.
     * 실패한 표는 -1 이고 다음 날 다시 돈다(행이 하루 더 남을 뿐이다).
     */
    public Purged purge(Instant now) {
        Instant cutoff = now.minus(props.rowRetention());
        int v = deleteOrWarn("email_verification", () -> verifications.deleteEndedBefore(cutoff));
        int t = deleteOrWarn("temporary_password", () -> temporaries.deleteEndedBefore(cutoff));
        log.info("인증 행 정리: email_verification={} temporary_password={} cutoff={}", v, t, cutoff);
        return new Purged(v, t);
    }

    private int deleteOrWarn(String table, IntSupplier delete) {
        try {
            Integer deleted = tx.execute(status -> delete.getAsInt());
            return deleted == null ? 0 : deleted;
        } catch (RuntimeException e) {
            log.warn("인증 행 정리 실패: table={} exception_type={}", table, e.getClass().getSimpleName(), e);
            return -1;
        }
    }

    /** IP 한도 표 정리와 행 정리(@Scheduled)를 쓰므로 스케줄링을 켠다. 다른 설정에 기대지 않는다 (여러 번 켜도 하나로 동작). */
    @Configuration
    @EnableScheduling
    static class SchedulingConfig {
    }
}
