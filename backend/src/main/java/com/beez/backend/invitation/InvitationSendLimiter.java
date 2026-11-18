package com.beez.backend.invitation;

import com.beez.backend.common.api.ApiException;
import com.beez.backend.common.concurrent.LogThrottle;
import com.beez.backend.common.concurrent.SlidingWindowCounter;
import com.beez.backend.config.BeezProperties;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 초대 메일 발송 한도 (Q05 결정 2026-11-09, S15P31A108-334). 초대한 계정마다 창 안에서 보낸 초대 메일 수를 센다.
 *
 * <ul>
 *   <li><b>계정 단위다.</b> 프로젝트 단위면 프로젝트를 새로 만들어 피해 간다. 계정을 새로 만드는 것은 가입 인증의 IP 한도(Q04)가 묶는다.</li>
 *   <li>MEM-A01 새 초대(INVITED) 한 건과 MEM-A11 다시 보내기 한 번이 각각 한 통이다. ALREADY_MEMBER·ALREADY_INVITED 는 메일을
 *       보내지 않아 세지 않는다.</li>
 *   <li>MEM-A01 은 보낼 통수를 한꺼번에 센다 — 남은 몫이 모자라면 요청 전체를 429 로 거부하고 아무것도 저장하지 않는다.
 *       일부만 초대하면 결과에 새 status 가 필요해 FE 계약이 바뀐다.</li>
 *   <li>메모리에만 센다 (재시작하면 처음부터, 서버 1대 기준 — Q04 IP 한도와 같다).</li>
 * </ul>
 */
@Component
public class InvitationSendLimiter {

    private static final Logger log = LoggerFactory.getLogger(InvitationSendLimiter.class);

    private final SlidingWindowCounter counter;
    private final LogThrottle warnThrottle = new LogThrottle(Duration.ofMinutes(10));

    public InvitationSendLimiter(BeezProperties properties) {
        BeezProperties.Invitation config = properties.invitation();
        this.counter = new SlidingWindowCounter(config.sendLimit(), config.sendLimitWindow());
    }

    /**
     * 이 계정이 mails 통을 더 보낼 수 있으면 세고 센 시각을 돌려준다({@link #release}에 넘긴다). 모자라면 429 rate_limited.
     */
    public Instant acquire(long inviterAccountId, int mails) {
        Instant now = Instant.now();
        if (counter.tryAcquire(key(inviterAccountId), mails, now)) {
            return now;
        }
        long skipped = warnThrottle.tryPass(now);
        if (skipped >= 0) {
            log.warn("초대 메일 한도 도달: account_id={} requested={} used={} limit={} window={} suppressed_since_last={}",
                    inviterAccountId, mails, counter.used(key(inviterAccountId), now), counter.limit(), counter.window(),
                    skipped);
        }
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                "초대 메일을 너무 많이 보냈습니다. 잠시 뒤에 다시 시도하세요");
    }

    /** {@link #acquire}로 센 것을 되돌린다. 저장이 되돌려졌거나 메일을 보내지 못한 요청이 한도를 쓰지 않게 한다. */
    public void release(long inviterAccountId, Instant acquiredAt, int mails) {
        counter.release(key(inviterAccountId), acquiredAt, mails);
    }

    /** 창 안에서 이 계정이 쓴 통수. 시험용. */
    int used(long inviterAccountId) {
        return counter.used(key(inviterAccountId), Instant.now());
    }

    /** 창이 지난 계정을 표에서 뺀다. */
    @Scheduled(fixedDelayString = "${beez.invitation.send-limit-purge-millis:600000}")
    public void purge() {
        counter.purge(Instant.now());
    }

    private static String key(long accountId) {
        return Long.toString(accountId);
    }
}
